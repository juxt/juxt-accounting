;; Copyright © 2013, JUXT LTD. All Rights Reserved.
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
    [edn :as edn]
    ]
   [clojure.tools
    [trace :refer (deftrace)]]
   [clojure.java [io :refer (reader resource)]]
   [clojure.tools.logging :refer :all]
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

(defprotocol DatabaseReference
  (as-db [_]))

(extend-protocol DatabaseReference
  datomic.db.Db
  (as-db [db] db)
  datomic.Connection
  (as-db [conn] (d/db conn))
  java.lang.String
  (as-db [dburi] (as-db (d/connect dburi))))

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
  (to-entity-map [id db] (d/entity (as-db db) id))
  clojure.lang.Keyword
  (to-ref-id [k] k)
  (to-entity-map [k db] (d/entity (as-db db) k))
  java.lang.String
  (to-ref-id [id] (to-ref-id (Long/parseLong id)))
  (to-entity-map [id db] (to-entity-map (Long/parseLong id) db))
  )

(def functions {:pro.juxt.accounting/generate-invoice-ref
                {:doc "Generate invoice reference"
                 :params '[db invoice prefix init]
                 :path "schema/pro/juxt/accounting/generate_invoice_ref.clj"}})

(defn create-functions [functions]
  (vec
   (for [[ident {:keys [doc params path]}] functions]
     {:db/id (d/tempid :db.part/user)
      :db/ident ident
      :db/doc doc
      :db/fn (d/function {:lang "clojure" :params params :code (slurp (resource path))})})))

(defn init [dburi]
  (if (d/create-database dburi)
    (debug "Created database" dburi)
    (debug "Using existing database" dburi))
  (let [conn (d/connect dburi)]
    (debug "Loading schema")
    @(d/transact conn (read-string (slurp (resource "schema.edn"))))
    @(d/transact conn (read-string (slurp (resource "data.edn"))))
    @(d/transact conn (create-functions functions))
    conn))

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
  [conn & {:keys [ident name code vat-no registered-address invoice-address invoice-addressee]}]
  {:pre [(conn? conn)
         (or (nil? ident) (keyword? ident))
         (or (nil? name) (string? name))
         (not (nil? ident))]}
  (let [legal-entity (d/tempid :db.part/user)]
    (->> [(when ident [:db/add legal-entity :db/ident ident])
          (when name [:db/add legal-entity :pro.juxt.accounting/name name])
          (when code [:db/add legal-entity :pro.juxt.accounting/code code])
          (when vat-no [:db/add legal-entity :pro.juxt.accounting/vat-number vat-no])
          (when invoice-addressee [:db/add legal-entity :pro.juxt.accounting/invoice-addressee invoice-addressee])
          (when registered-address [:db/add legal-entity :pro.juxt.accounting/registered-address (str registered-address)])
          (when invoice-address [:db/add legal-entity :pro.juxt.accounting/invoice-address (str invoice-address)])]
         (remove nil?) vec
         (transact-insert conn legal-entity))))

(defn create-account!
  "Create an account and return its id."
  [conn & {:keys [entity type ^CurrencyUnit currency ^String description ^String account-no ^String sort-code]}]
  {:pre [(not (nil? currency))]}
  (let [account (d/tempid :db.part/user)]
    (->> [(when entity [:db/add account :pro.juxt.accounting/entity (to-ref-id entity)])
          (when type [:db/add account :pro.juxt.accounting/account-type type])
          [:db/add account :pro.juxt.accounting/currency (.getCode currency)]
          (when description [:db/add account :pro.juxt/description description])
          (when account-no [:db/add account :pro.juxt.accounting/account-number account-no])
          (when sort-code [:db/add account :pro.juxt.accounting/sort-code sort-code])]
         (remove nil?) vec
         (transact-insert conn account))))

(defn find-account
  "Find an entity's account of a given type"
  [db {:keys [entity type]}]
  {:pre [(not (nil? entity))
         (not (nil? type))]}
  (d/entity db
            (ffirst
             (q '[:find ?account
                  :in $ ?entity ?type
                  :where
                  [?account :pro.juxt.accounting/entity ?entity]
                  [?account :pro.juxt.accounting/account-type ?type]]
                (as-db db) (to-ref-id entity) type))))

(defn get-accounts
  "Get all the accounts"
  [db]
  (map (partial zipmap [:account :entity :entity-name :entity-ident :type :currency])
       (q '[:find ?account ?entity ?entity-name ?entity-ident ?type ?currency
            :in $
            :where
            [?account :pro.juxt.accounting/entity ?entity]
            [?entity :pro.juxt.accounting/name ?entity-name]
            [?entity :db/ident ?entity-ident]
            [?account :pro.juxt.accounting/account-type ?type]
            [?account :pro.juxt.accounting/currency ?currency]]
          (as-db db))))

;; For example
;; {:debit-account :foo :credit-account :bar :amount (Money/parse "GBP 20")}
(defn assemble-transaction
  "Assemble the Datomic txdata for a financial transaction. All entries
  in the accounting system are created via this function. It is
  responsible for checking that the debits and credits balance, thereby
  ensuring that all the accounts balance. The given txid should be a key
  generated from the :db.part/tx partition. Each entry pair is a vector
  containing a debit and a credit. Each debit/credit is a map with keys
  for the account and Money amount."
  [db txid date transactions]
  {:pre [(pos? (count transactions))
         (db? db)
         (not (nil? date))
         (instance? Date (to-date date))
         (coll? transactions)
         (every? (comp not empty?) transactions)
         (every? map? transactions)
         (every? (comp (partial instance? Money) :amount) transactions)
         (every? (comp string? :description) transactions)
         ]}

  ;; Check that all amounts are in the same currency as their
  ;; corresponding credit and debit accounts

  ;; TODO, or if they are in different currencies, record the fxrate as
  ;; an fxrate attribute in the double-entry entity.
  (doseq [{:keys [debit-account credit-account amount]} transactions]
    (when-not (= (.getCode (.getCurrencyUnit amount))
                 (:pro.juxt.accounting/currency (to-entity-map debit-account db))
                 (:pro.juxt.accounting/currency (to-entity-map credit-account db)))
      (throw (ex-info "Entry amount is in a different currency to that of the account"
                      {:amount-currency (.getCurrencyUnit amount)
                       :debit-account-currency (:pro.juxt.accounting/currency (to-entity-map debit-account db))
                       :credit-account-currency (:pro.juxt.accounting/currency (to-entity-map credit-account db))}))))

  (concat
   (apply concat
          (for [{:keys [debit-account credit-account ^Money amount ^String description]} transactions]
            (let [node (d/tempid :db.part/user)]
              [[:db/add (to-ref-id debit-account) :pro.juxt.accounting/debit node]
               [:db/add (to-ref-id credit-account) :pro.juxt.accounting/credit node]
               [:db/add node :pro.juxt.accounting/amount (.getAmount amount)]
               [:db/add node :pro.juxt.accounting/currency (.getCode (.getCurrencyUnit amount))]
               [:db/add node :pro.juxt/description description]])))
   [[:db/add txid :pro.juxt.accounting/date (to-date date)]]))

(defn get-entries [db account type]
  (map (fn [[date account amount currency tx entry description]]
         {:date date
          :account account
          :invoice (:pro.juxt.accounting/invoice (d/entity (as-db db) entry))
          :entry entry
          :type type
          :amount (Money/of (CurrencyUnit/getInstance currency) amount)
          :tx tx
          :description description
          })
       (q {:find '[?date ?account ?amount ?currency ?tx ?entry ?description]
           :in '[$ ?account]
           :where [['?account type '?entry]
                   '[?account :pro.juxt.accounting/currency ?currency]
                   '[?entry :pro.juxt.accounting/amount ?amount ?tx]
                   '[?entry :pro.juxt/description ?description]
                   '[?tx :pro.juxt.accounting/date ?date]
                   ]} (as-db db) (to-ref-id account))))

(defn get-amounts [db account type]
    (map (fn [[amount currency]] (Money/of (CurrencyUnit/getInstance currency) amount))
         (q {:find '[?amount ?currency]
             :in '[$ ?account]
             :with '[?entry]
             :where [['?account type '?entry]
                     '[?account :pro.juxt.accounting/currency ?currency]
                     '[?entry :pro.juxt.accounting/amount ?amount]
                     ]} (as-db db) (to-ref-id account))))

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

(defn get-invoices
  [db]
  (->> (q '[:find ?invoice ?invoice-ref ?entity-name ?subtotal ?output-tax ?total ?invoice-date ?issue-date
            :in $
            :where
            [?entry :pro.juxt.accounting/invoice ?invoice]
            [?invoice :pro.juxt.accounting/invoice-ref ?invoice-ref]
            [?invoice :pro.juxt.accounting/entity ?entity]
            [?invoice :pro.juxt.accounting/subtotal ?subtotal]
            [?invoice :pro.juxt.accounting/output-tax ?output-tax]
            [?invoice :pro.juxt.accounting/total ?total]
            [?invoice :pro.juxt.accounting/invoice-date ?invoice-date]
            [?invoice :pro.juxt.accounting/issue-date ?issue-date]
            [?entity :pro.juxt.accounting/name ?entity-name]
            ]
          (as-db db))
       (map (fn [[invoice invoice-ref entity-name subtotal output-tax total invoice-date issue-date]]
              (let [ent (d/entity (as-db db) invoice)]
                ;; TODO: Replace with map-map
                {:invoice invoice
                 :invoice-ref invoice-ref
                 :entity-name entity-name
                 :subtotal subtotal
                 :output-tax output-tax
                 :total total
                 :invoice-date invoice-date
                 :issue-date issue-date
                 :output-tax-paid (:pro.juxt.accounting/output-tax-paid ent)})))
       (sort-by :invoice-date)))

(defn find-invoice-by-ref [db invoice-ref]
  (->
   (q '[:find ?invoice
        :in $ ?invoice-ref
        :where [?invoice :pro.juxt.accounting/invoice-ref ?invoice-ref]]
      db invoice-ref)
   ffirst
   (to-entity-map db)))

(defn get-vat-returns
  [db]
  (->> (q '[:find ?date ?box-1 ?box-6
            :in $
            :where
            [?return :pro.juxt.accounting/date ?date]
            [?return :pro.juxt.accounting.vat/box-1 ?box-1]
            [?return :pro.juxt.accounting.vat/box-6 ?box-6]
            ]
          (as-db db))
       (map (partial zipmap [:date :box1 :box6]))
       (sort-by :date)))
