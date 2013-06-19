;; Copyright © 2013, JUXT Ltd. All Rights Reserved.
;;
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;;
;; By using this software in any fashion, you are agreeing to be bound by the
;; terms of this license.
;;
;; You must not remove this notice, or any other, from this software.
(ns pro.juxt.accounting.database
  "Database access functions."
  (:require
   [clojure
    [set :as set]
    [edn :as edn]]
   [clojure.java [io :refer (reader)]]
   [taoensso.timbre :as timbre :refer (trace debug info warn error fatal spy)]
   [datomic.api :refer (q db transact transact-async entity) :as d]
   [clojurewerkz.money.amounts :as ma :refer (total zero)])
  (:import (org.joda.money Money CurrencyUnit)))

(def ^:dynamic *dburi* "The Datomic database uri, Set by nrepl handlers, don't assume in functions." nil)

(def functions {})

(defn create-functions [functions]
  (vec
   (for [[ident {:keys [doc params path]}] functions]
     {:db/id (d/tempid :db.part/user)
      :db/ident ident
      :db/doc doc
      :db/fn (d/function {:lang "clojure" :params params :code (slurp path)})})))

(defn init [dburi]
  (if (d/create-database dburi)
    (debug "Created database" dburi)
    (debug "Using existing database" dburi))
  (let [conn (d/connect dburi)]
    (debug "Loading schema")
    @(d/transact conn (read-string (slurp "resources/schema.edn")))
    @(d/transact conn (read-string (slurp "resources/data.edn")))
    @(d/transact conn (create-functions functions))))

(defmacro insert
  "Blocking update. Returns the id corresponding to the given symbol."
  [conn temp stmts]
  `(let [~temp (d/tempid :db.part/user)
         {:keys [~'db-after ~'tempids]}
         @(transact ~conn (vec ~stmts))]
     (d/resolve-tempid ~'db-after ~'tempids ~temp)))

(defn create-account!
  "Create an account and return its id."
  [conn ^String name ^CurrencyUnit currency & {:keys [^String description]}]
  (->> [[:db/add account :pro.juxt/name name]
        [:db/add account :pro.juxt.accounting/currency (.getCode currency)]
        (when description [:db/add account :pro.juxt/description description])]
       (remove nil?) vec
       (insert conn account)))

(defn get-name
  "Get the unique name of the given entity."
  [db e]
  (:pro.juxt/name (d/entity db e)))

(defn get-description
  "Get the description of the given entity."
  [db e]
  (:pro.juxt/description (d/entity db e)))

(defprotocol TransactionDate
  (to-date [_]))

(extend-protocol TransactionDate
  org.joda.time.LocalDate
  (to-date [ld] (.toDate (.toDateMidnight ld org.joda.time.DateTimeZone/UTC)))
  org.joda.time.DateTime
  (to-date [dt] (.toDate dt)))

(defn create-entry [conn debit-account credit-account ^Money amount & {:keys [date description instance-of]}]
  (let [transaction (d/tempid :db.part/user)]
    (->> [[:db/add debit-account :pro.juxt.accounting/debit transaction]
          [:db/add credit-account :pro.juxt.accounting/credit transaction]
          [:db/add transaction :pro.juxt.accounting/amount (.getAmount amount)]
          [:db/add transaction :pro.juxt.accounting/currency (.getCode (.getCurrencyUnit amount))]
          (when date [:db/add transaction :pro.juxt.accounting/date (to-date date)])
          (when description [:db/add transaction :pro.juxt/description description])
          (when instance-of [:db/add transaction :pro.juxt.accounting/instance-of instance-of])]
         (remove nil?) vec
         (transact-async conn))))

(defn- get-transactions [db account type]
  (map (fn [[amount currency]] (Money/of (CurrencyUnit/getInstance currency) amount))
       (q {:find '[?amount ?currency]
           :in '[$ ?account]
           :with '[?debit]
           :where [['?account type '?debit]
                   '[?debit :pro.juxt.accounting/amount ?amount]
                   '[?debit :pro.juxt.accounting/currency ?currency]]
           } db account)))

(defn get-debits [db account]
  (get-transactions db account :pro.juxt.accounting/debit))

(defn get-credits [db account]
  (get-transactions db account :pro.juxt.accounting/credit))

(defn get-total [db account monies]
  (if (empty? monies)
    (zero (CurrencyUnit/getInstance (:pro.juxt.accounting/currency (entity db account))))
    (total monies)))

(defn get-total-debit [db account]
  (get-total db account (get-debits db account)))

(defn get-total-credit [db account]
  (get-total db account (get-credits db account)))

(defn get-balance
  "The debits of a given account, minus its credits."
  [db account]
  (. (get-total-debit db account) minus (get-total-credit db account)))

(defn reconcile-accounts
  "Reconcile accounts."
  [db & accounts]
  (when (not-empty accounts)
    (total (map #(get-balance db %) accounts))))
