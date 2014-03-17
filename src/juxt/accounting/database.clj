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
(ns juxt.accounting.database
  "Database access functions."
  (:refer-clojure :exclude (read-string))
  (:require
   jig
   [clojure
    [set :as set]
    [edn :as edn]
    ]
   [clojure.tools
    [trace :refer (deftrace)]]
   [clojure.java [io :refer (reader resource)]]
   [clojure.tools.logging :refer :all]
   [datomic.api :refer (q db transact transact-async entity) :as d]
   [juxt.datomic.extras :refer (read-string to-ref-id as-db db? to-entity-map)]
   [juxt.datomic.extras.spider :refer (spider)]
   [clojurewerkz.money.amounts :as ma :refer (total zero)]
   [clojurewerkz.money.currencies :refer (to-currency-unit)]
   [juxt.accounting.money :refer (as-money)])
  (:import (jig Lifecycle)))

;;(d/q '[:find ?a ?v :in $ :where [17592186045465 ?a ?v]] (as-db "datomic:mem://juxt/accounts"))

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
  java.util.Date
  (to-date [d] d)
  Long
  (to-date [l] (java.util.Date. l)))

(def functions
  {:juxt.accounting/generate-invoice-ref
   {:doc "Generate invoice reference"
    :params '[db invoice prefix init]
    :path "schema/juxt/accounting/generate_invoice_ref.clj"}})

(defn create-functions [functions]
  (vec
   (for [[ident {:keys [doc params path]}] functions]
     {:db/id (d/tempid :db.part/user)
      :db/ident ident
      :db/doc doc
      :db/fn (d/function {:lang "clojure" :params params :code (slurp (resource path))})})))

(defn init [dburi]
  {:pre [dburi]}
  (if (d/create-database dburi)
    (debug "Created database" dburi)
    (debug "Using existing database" dburi))
  (let [conn (d/connect dburi)]
    (debug "Loading schema")
    @(d/transact conn (read-string (slurp (resource "schema.edn"))))
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
    (->> [[:db/add legal-entity :juxt/type :entity]
          (when ident [:db/add legal-entity :db/ident ident])
          (when name [:db/add legal-entity :juxt.accounting/name name])
          (when code [:db/add legal-entity :juxt.accounting/code code])
          (when vat-no [:db/add legal-entity :juxt.accounting/vat-number vat-no])
          (when invoice-addressee [:db/add legal-entity :juxt.accounting/invoice-addressee invoice-addressee])
          (when registered-address [:db/add legal-entity :juxt.accounting/registered-address (str registered-address)])
          (when invoice-address [:db/add legal-entity :juxt.accounting/invoice-address (str invoice-address)])]
         (remove nil?) vec
         (transact-insert conn legal-entity))))

(defn create-account!
  "Create an account and return its id."
  [conn & {:keys [ident entity ^org.joda.money.CurrencyUnit currency ^String name ^String description ^String account-no ^String sort-code]}]
  {:pre [(not (nil? currency))]}
  (let [account (d/tempid :db.part/user)]
    (->> [[:db/add account :db/ident ident]
          (when entity [:db/add account :juxt.accounting/entity (to-ref-id entity)])
          [:db/add account :juxt.accounting/currency (.getCode currency)]
          [:db/add account :juxt/type :account]
          (when name [:db/add account :juxt.accounting/name name])
          (when description [:db/add account :juxt/description description])
          (when account-no [:db/add account :juxt.accounting/account-number account-no])
          (when sort-code [:db/add account :juxt.accounting/sort-code sort-code])]
         (remove nil?) vec
         (transact-insert conn account))))

(defn get-legal-entities
  "Get all the entties"
  [db]
  (let [db (as-db db)]
    (for [[e]
          (q '[:find ?e :in $
               :where
               [?e :juxt/type :entity]]
             db)]
      (into {} (to-entity-map e db)))))

(defn get-legal-entities-as-table
  "Get all the entties"
  [db]
  (map #(spider %
                {:ident :db/ident
                 :name :juxt.accounting/name})
       (get-legal-entities db)))

(defn get-accounts
  "Get all the accounts"
  [db]
  (let [db (as-db db)]
    (for [[a e]
          (q '[:find ?account ?entity :in $
               :where
               [?account :juxt/type :account]
               [?account :juxt.accounting/entity ?entity]]
             db)]
      (-> (into {} (to-entity-map a db))
          (assoc :entity (into {} (to-entity-map e db)))))))

(defn get-accounts-as-table [db]
  (map #(spider
         %
         {:ident [:db/ident]
          :account-name :juxt.accounting/name
          :account-no :juxt.accounting/account-number
          :sort-code :juxt.accounting/sort-code
          :currency :juxt.accounting/currency
          :entity [:entity :db/ident]
          :entity-name [:entity :juxt.accounting/name]})
       (get-accounts db)))

(defn find-account [db & {:as keyvals}]
  {:post [%]}
  (let [squash-sort-code        ; to avoid inequality due to punctuation
        (fn [m]
          (if (:sort-code m)
            (update-in m [:sort-code] clojure.string/replace "-" "")
            m))
        matches (filter #(apply = (map squash-sort-code
                                       [keyvals (select-keys % (keys keyvals))]))
                        (get-accounts-as-table db))]
    (if (= (count matches) 1)
      (first matches) ; return the account
      (throw (ex-info (cond (empty? matches)
                            "No account matches criteria"
                            (>= (count matches) 2)
                            "More than one account matches")
                      {:criteria keyvals
                       :matching-accounts matches})))))

(defn assemble-transaction
  "Assemble the Datomic txdata for a financial transaction. All entries
  in the accounting system are created via this function. It is
  responsible for checking that the debits and credits balance, thereby
  ensuring that all the accounts balance. Entries can have multiple
  components. Each component is a vector containing a debit and a
  credit. Each debit/credit is a map with keys for the account and Money
  amount."
  [db date components txdescription]
  {:pre [(pos? (count components))
         (db? db)
         (not (nil? date))
         (instance? java.util.Date (to-date date))
         (coll? components)
         (every? (comp not empty?) components)
         (every? map? components)
         (every? (comp string? :description) components)
         ]}

  ;; TODO: Check that all amounts are in the same currency as their
  ;; corresponding credit and debit accounts

  ;; TODO, or if they are in different currencies, record the fxrate as
  ;; an fxrate attribute in the double-entry entity.
  (doseq [{:keys [debit-account credit-account amount]} components]
    (cond
     (nil? (:db/id (to-entity-map debit-account db)))
     (throw (ex-info (format "Debit account does not exist: %s" debit-account) {}))
     (nil? (:db/id (to-entity-map credit-account db)))
     (throw (ex-info (format "Credit account does not exist: %s" credit-account) {}))
     (nil? (:juxt.accounting/currency (to-entity-map debit-account db)))
     (throw (ex-info (format "Debit account does not have a currency: %s" debit-account) {}))
     (nil? (:juxt.accounting/currency (to-entity-map credit-account db)))
     (throw (ex-info (format "Credit account does not have a currency: %s" credit-account) {}))
     (not= (:juxt.accounting/currency (to-entity-map debit-account db))
           (:juxt.accounting/currency (to-entity-map credit-account db)))
     (throw (ex-info "Accounts must be denonminated in the same currency"
                      {:debit-account-currency (:juxt.accounting/currency (to-entity-map debit-account db))
                       :credit-account-currency (:juxt.accounting/currency (to-entity-map credit-account db))}))))

  (let [entry (d/tempid :db.part/user)
        txid (d/tempid :db.part/tx)]
    (concat
     [[:db/add entry :juxt.accounting/date date]]
     (apply concat
            (for [{:keys [debit-account credit-account amount ^String description component-type]} components]
              (let [amount (as-money amount (:juxt.accounting/currency (to-entity-map debit-account db)))
                    component (d/tempid :db.part/user)]
                (remove nil?
                        [
                         [:db/add entry :juxt.accounting/component component]
                         [:db/add (to-ref-id debit-account) :juxt.accounting/debit component]
                         [:db/add (to-ref-id credit-account) :juxt.accounting/credit component]
                         (when component-type [:db/add component :juxt/type component-type])
                         [:db/add component :juxt.accounting/amount (.getAmount amount)]
                         [:db/add component :juxt.accounting/currency (.getCode (.getCurrencyUnit amount))]
                         [:db/add component :juxt/description description]]))))
     ;; TX metadata
     [[:db/add txid :juxt/description txdescription]])))

(defn get-account-components
  [db account type]
  (let [db (as-db db)
        rtype (case type
                :juxt.accounting/debit :juxt.accounting/credit
                :juxt.accounting/credit :juxt.accounting/debit)]
    (for [sol
          (q {:find '[?entry ?component ?account ?other-account ?tx]
              :in '[$ ?account]
              :where [['?account type '?component]
                      ['?other-account rtype '?component]
                      ['?entry :juxt.accounting/component '?component '?tx]
                      ]} db (to-ref-id account))]
      (spider (zipmap [:entry :component :account :other-account :txdesc] (map #(to-entity-map % db) sol))
              {:id [:component :db/id]
               :entry [:entry :db/id]
               :date [:entry :juxt.accounting/date]
               :type [(constantly type)]
               :component-type [:component :juxt/type]
               :account [:account]
               :other-account [:other-account]
               :description [:component :juxt/description]
               :value [:component (juxt :juxt.accounting/amount :juxt.accounting/currency) (partial apply as-money)]
               :txdesc [:txdesc :juxt/description]
               :invoice-item [:component :juxt.accounting/_invoice-item-component]
               }))))

(defn get-all-components
  [db type]
  (let [db (as-db db)
        rtype (case type
                :juxt.accounting/debit :juxt.accounting/credit
                :juxt.accounting/credit :juxt.accounting/debit)]
    (for [sol
          (q {:find '[?entry ?component ?account ?other-account ?tx]
              :where [['?account type '?component]
                      ['?other-account rtype '?component]
                      ['?entry :juxt.accounting/component '?component '?tx]
                      ]} db)]
      (spider (zipmap [:entry :component :account :other-account :txdesc] (map #(to-entity-map % db) sol))
              {:id [:component :db/id]
               :entry [:entry :db/id]
               :date [:entry :juxt.accounting/date]
               :type [(constantly type)]
               :component-type [:component :juxt/type]
               :account [:account]
               :other-account [:other-account]
               :description [:component :juxt/description]
               :value [:component (juxt :juxt.accounting/amount :juxt.accounting/currency) (partial apply as-money)]
               :txdesc [:txdesc :juxt/description]
               :invoice-item [:component :juxt.accounting/_invoice-item-component]
               }))))

(defn count-account-components [db account]
  (let [db (as-db db)]
    (+ (count (get-account-components db account :juxt.accounting/debit))
       (count (get-account-components db account :juxt.accounting/credit))))
)

(defn get-account-entries [components]
  (->> components (group-by :entry) (map :second)))

(defn get-total [db account monies]
  (if (empty? monies)
    (zero (to-currency-unit (:juxt.accounting/currency (to-entity-map account db))))
    (total monies)))

(defn get-balance [db account]
  (let [db (as-db db)]
    (. (get-total db account (map :value (get-account-components db account :juxt.accounting/debit)))
       minus
       (get-total db account (map :value (get-account-components db account :juxt.accounting/credit))))))

(defn get-accounts-as-table [db]
  (map #(spider
         %
         {:ident [:db/ident]
          :account-name [:juxt.accounting/name]
          :account-no [:juxt.accounting/account-number]
          :sort-code [:juxt.accounting/sort-code]
          :currency [:juxt.accounting/currency]
          :entity [:entity :db/ident]
          :entity-name [:entity :juxt.accounting/name]})
       (get-accounts db)))

#_(defn reconcile-accounts
  "Reconcile accounts."
  [db & accounts]
  (when (not-empty accounts)
    (total (map #(get-balance db %) accounts))))

(defn get-invoices [db]
  (let [db (as-db db)]
    (->>
     ;; Find
     (q '[:find ?invoice ?entity
          :in $
          :where
          [?invoice :juxt.accounting/entity ?entity]
          [?invoice :juxt.accounting/invoice-date ?invoice-date]
          ]
        db)
     ;; Transform each entity to an entity map
     (map (partial map #(to-entity-map % db)))
     ;; Key entities
     (map (partial zipmap [:invoice :entity]))
     ;; Traverse attributes
     (map #(spider %
                   {:invoice [:invoice :db/id]
                    :invoice-ref [:invoice :juxt.accounting/invoice-ref]
                    :entity [:entity :db/ident]
                    :items [:invoice :juxt.accounting/item]
                    :item-count [:invoice :juxt.accounting/item count]
                    :invoice-date [:invoice :juxt.accounting/invoice-date]
                    :issue-date [:invoice :juxt.accounting/issue-date]
                    :total [:invoice :juxt.accounting/total]
                    :subtotal [:invoice :juxt.accounting/subtotal]
                    :vat [:invoice :juxt.accounting/vat]
                    }))
     ;; Sort
     (sort-by :invoice-date))))

(defn find-invoice-by-ref [db invoice-ref]
  (->
   (q '[:find ?invoice
        :in $ ?invoice-ref
        :where [?invoice :juxt.accounting/invoice-ref ?invoice-ref]]
      db invoice-ref)
   ffirst
   (to-entity-map db)))

(defn get-vat-returns
  [db]
  (->> (q '[:find ?date ?box-1 ?box-6
            :in $
            :where
            [?return :juxt.accounting/date ?date]
            [?return :juxt.accounting.vat/box-1 ?box-1]
            [?return :juxt.accounting.vat/box-6 ?box-6]
            ]
          (as-db db))
       (map (partial zipmap [:date :box1 :box6]))
       (sort-by :date)))

(deftype Database [config]
  Lifecycle
  (init [_ system]
    (let [dburi (-> config :db :uri)]
      (when-not (-> config :db :persistent)
        (infof "Deleting database: %s" dburi)
        (d/delete-database dburi))
      (infof "Initializing database: %s" dburi)
      (init dburi)
      (assoc system :dburi dburi)))
  (start [_ system]
    (infof "Starting Database component")
    system)
  (stop [_ system]
    (when-not (-> config :db :persistent)
      (d/delete-database (-> config :db :uri)))
    (d/shutdown false)
    system))
