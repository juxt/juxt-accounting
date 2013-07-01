;; Copyright © 2013, JUXT Ltd. All Rights Reserved.
;;
;; This file is part of JUXT Accounting.
;;
;; JUXT Accounting is free software: you can redistribute it and/or modify it under the
;; terms of the GNU Affero General Public License as published by the Free
;; Software Foundation, either version 3 of the License, or (at your option) any
;; later version.
;;
;; JUXT Accounting is distributed in the hope that it will be useful but WITHOUT ANY
;; WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
;; A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
;; details.
;;
;; Please see the LICENSE file for a copy of the GNU Affero General Public License.
;;
(ns pro.juxt.accounting.database
  "Database access functions."
  (:require
   [clojure
    [set :as set]
    [edn :as edn]]
   [clojure.java [io :refer (reader resource)]]
   [taoensso.timbre :as timbre :refer (trace debug info warn error fatal spy)]
   [datomic.api :refer (q db transact transact-async entity) :as d]
   [clojurewerkz.money.amounts :as ma :refer (total zero)])
  (:import (java.util Date)
           (org.joda.money Money CurrencyUnit)))

(defn db?
  "Check type is a Datomic database value. Useful for pre and post conditions."
  [db]
  (instance? datomic.db.Db db))

(defn conn?
  "Check type is a Datomic connection. Useful for pre and post conditions."
  [conn]
  (instance? datomic.Connection conn))

(def id? "Is this a valid Datomic id? (Must be positive). Useful for assertions."
  (every-pred number? pos?))

(defn entity? [e] "Is this a valid Datomic entity? (Must be a map and contain :db:id)."
  (:db/id e))

(defprotocol TransactionDate
  (to-date [_]))

(extend-protocol TransactionDate
  org.joda.time.LocalDate
  (to-date [ld] (.toDate (.toDateMidnight ld org.joda.time.DateTimeZone/UTC)))
  org.joda.time.DateTime
  (to-date [dt] (.toDate dt))
  Date
  (to-date [d] d)
  Long
  (to-date [l] (Date. l)))

(defprotocol EntityReference
  (to-ref-id [_])
  (to-entity-map [_ db]))

(extend-protocol EntityReference
  datomic.query.EntityMap
  (to-ref-id [em] (:db/id em))
  (to-entity-map [em _] em)
  java.lang.Long
  (to-ref-id [id] id)
  (to-entity-map [id db] (d/entity db id))
  clojure.lang.Keyword
  (to-ref-id [k] k)
  (to-entity-map [k db] (d/entity db k))
  )

(def functions {:pro.juxt.accounting/generate-invoice-ref
                {:doc "Generate invoice reference"
                 :params '[db invoice prefix init]
                 :path "resources/schema/pro/juxt/accounting/generate_invoice_ref.clj"}})

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
    @(d/transact conn (read-string (slurp (resource "schema.edn"))))
    @(d/transact conn (read-string (slurp (resource "data.edn"))))
    @(d/transact conn (create-functions functions))))

(defn transact-insert
  "Blocking update. Returns the entity map of the new entity."
  [conn temps txdata]
  {:pre [(conn? conn)
         (or (number? temps) (coll? temps))
         (coll? txdata)]}
  (let [{:keys [db-after tempids]} @(transact conn (vec txdata))]
    (if (vector? temps)
      (map (comp (partial d/entity db-after)
                 (partial d/resolve-tempid db-after tempids)) temps)
      (d/entity db-after (d/resolve-tempid db-after tempids temps)))))

(defn create-legal-entity!
  "Create a legal entity and return its id."
  [conn & {:keys [ident name postal-address principal]}]
  {:pre [(conn? conn)
         (or (nil? ident) (keyword? ident))
         (or (nil? name) (string? name))
         (not (nil? ident))]}
  (let [legal-entity (d/tempid :db.part/user)]
    (->> [(when ident [:db/add legal-entity :db/ident ident])
          (when name [:db/add legal-entity :pro.juxt.accounting/name name])
          (when principal [:db/add legal-entity :pro.juxt.accounting/principal principal])
          (when postal-address [:db/add legal-entity :pro.juxt.accounting/postal-address (str postal-address)])]
         (remove nil?) vec
         (transact-insert conn legal-entity))))

(defn create-account!
  "Create an account and return its id."
  [conn & {:keys [parent ident ^CurrencyUnit currency ^String description]}]
  {:pre [(not (nil? currency))
         (or (nil? ident) (keyword? ident))
         ]}
  (let [account (d/tempid :db.part/user)]
    (->> [(when parent [:db/add account :pro.juxt.accounting/parent (to-ref-id parent)])
          (when ident [:db/add account :db/ident ident])
          [:db/add account :pro.juxt.accounting/currency (.getCode currency)]
          (when description [:db/add account :pro.juxt/description description])]
         (remove nil?) vec
         (transact-insert conn account))))

(defn assemble-transaction
  "Assemble the Datomic txdata for a financial transaction. All entries
  in the accounting system are created via this function. It is
  responsible for ensuring that the debits and credits balance, thereby
  ensuring that all the accounts balance. The given txid should be a key generated from the :db.part/tx partition."
  [db txid & {:keys [date debits credits]}]
  {:pre [(db? db)
         (not (nil? date))
         (instance? Date (to-date date))
         (map? debits)
         (map? credits)
         (every? (partial instance? Money) (concat (vals debits) (vals credits)))]}

  ;; Check that all credits and of the same currency
  (doseq [[account amount] (concat (seq debits) (seq credits))]
    (when-not (= (.getCode (.getCurrencyUnit amount))
                 (:pro.juxt.accounting/currency (to-entity-map account db)))
      (cond (nil? (:pro.juxt.accounting/currency (to-entity-map account db)))
            (throw (ex-info (str "Account " (to-ref-id account) " not found or has no associated currency") {:account (to-ref-id account)}))
            :otherwise
            (throw (ex-info "Entry amount is in a different currency to that of the account"
                            {:entry-currency (.getCurrencyUnit amount)
                             :account-currency (:pro.juxt.accounting/currency (to-entity-map account db))})))))

  ;; This important guard is only for when there is a single currency across all entries
  ;; Multi-FX transactions can't be checked in this way so are simply accepted.
  (when (= 1 (count (distinct (map (fn [[_ amount]] (.getCurrencyUnit amount)) (concat (seq debits) (seq credits))))))
    (cond (zero? (count debits))
          (throw (ex-info "Debits must contain one or more entries" {}))
          (zero? (count credits))
          (throw (ex-info "Credits must contain one or more entries" {}))
          (not= (total (vals debits)) (total (vals credits)))
          (throw (ex-info "Debits do not balance with credits" {:debit-total (total (vals debits))
                                                                :debits (vals debits)
                                                                :credit-total (total (vals credits))
                                                                :credits (vals credits)})))
    (concat
     (apply concat
            (for [[^long account ^Money amount] debits]
              (let [entry (d/tempid :db.part/user)]
                [[:db/add (to-ref-id account) :pro.juxt.accounting/debit entry]
                 [:db/add entry :pro.juxt.accounting/amount (.getAmount amount)]])))
     (apply concat
            (for [[^long account ^Money amount] credits]
              (let [entry (d/tempid :db.part/user)]
                [[:db/add (to-ref-id account) :pro.juxt.accounting/credit entry]
                 [:db/add entry :pro.juxt.accounting/amount (.getAmount amount)]])))
     [[:db/add txid :pro.juxt.accounting/date (to-date date)]])))

(defn get-entries [db account type]
  (map (fn [[account amount currency tx entry]]
         {:account account
          :invoice (:pro.juxt.accounting/invoice (d/entity db entry))
          :entry entry
          :type type
          :amount (Money/of (CurrencyUnit/getInstance currency) amount)
          :tx tx})
       (q {:find '[?account ?amount ?currency ?tx ?entry]
           :in '[$ ?account]
           :where [['?account type '?entry]
                   '[?account :pro.juxt.accounting/currency ?currency]
                   '[?entry :pro.juxt.accounting/amount ?amount ?tx]
                   ]} db (to-ref-id account))))

(defn get-amounts [db account type]
    (map (fn [[amount currency]] (Money/of (CurrencyUnit/getInstance currency) amount))
         (q {:find '[?amount ?currency]
             :in '[$ ?account]
             :with '[?entry]
             :where [['?account type '?entry]
                     '[?account :pro.juxt.accounting/currency ?currency]
                     '[?entry :pro.juxt.accounting/amount ?amount]
                     ]} db (to-ref-id account))))

(defn get-debits [db account]
  (get-entries db account :pro.juxt.accounting/debit))

(defn get-debit-amounts [db account]
  (get-amounts db account :pro.juxt.accounting/debit))

(defn get-credits [db account]
  (get-entries db account :pro.juxt.accounting/credit))

(defn get-credit-amounts [db account]
  (get-amounts db account :pro.juxt.accounting/credit))

(defn get-total [db account monies]
  (if (empty? monies)
    (zero (CurrencyUnit/getInstance (:pro.juxt.accounting/currency (to-entity-map account db))))
    (total monies)))

(defn get-total-debit [db account]
  (get-total db account (get-debit-amounts db account)))

(defn get-total-credit [db account]
  (get-total db account (get-credit-amounts db account)))

(defn get-balance
  "The debits of a given account, minus its credits."
  [db account]
  (. (get-total-debit db account) minus (get-total-credit db account)))

(defn reconcile-accounts
  "Reconcile accounts."
  [db & accounts]
  (when (not-empty accounts)
    (total (map #(get-balance db %) accounts))))
