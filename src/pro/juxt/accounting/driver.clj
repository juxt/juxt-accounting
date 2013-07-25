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
(ns pro.juxt.accounting.driver
  (:require
   [clojure.pprint :refer (pprint)]
   [clojure.edn :as edn]
   [clojure.java.io :refer (file resource)]
   [datomic.api :as d]
   [clj-time.core :as time]
   [pro.juxt.accounting
    [database :as db]
    [invoicing :as invoicing]])
  (:import
   (java.io File)
   (org.joda.time DateTime)
   (org.joda.money CurrencyUnit Money)))

(defn load-transactions [conn {:keys [loader input source] :as args}]
  (require (symbol (namespace loader)))
  (let [ldr (ns-resolve (symbol (namespace loader)) (symbol (name loader)))
        db (d/db conn)]
    (when-not ldr (throw (ex-info "Failed to resolve loader" {:namespace (namespace loader)
                                                              :name (name loader)})))
    (let [billing-file (file (.getParentFile source) input)]
      (when-not (.exists billing-file) (throw (ex-info "Billing input file does not exist" {:billing-file billing-file})))
      (doseq [billing (edn/read-string (slurp billing-file))]
        (let [bookentry (ldr db args billing)] ; should ldr be called transformer?
          @(d/transact
            conn
            (let [txid (d/tempid :db.part/tx)]
              (concat
               (for [[n v] (:metadata bookentry)]
                 [:db/add txid n v])
               (db/assemble-transaction
                db txid
                :date (or (:date bookentry) (:date billing))
                :debits (:debits bookentry)
                :credits (:credits bookentry))))))))))

(defn process-accounts-file [path dburi]
  (let [fl (file path)
        {:keys [entities accounts transactions invoices]}
        (edn/read-string
         {:readers {'pro.juxt.accounting/currency
                    (fn [x] (CurrencyUnit/getInstance (str x)))}}
         (slurp fl))]

    (let [conn (d/connect dburi)]
      (doseq [[ident {:keys [name code vat-no registered-address invoice-address invoice-addressee]}] entities]
        (db/create-legal-entity!
         conn
         :ident ident
         :name name
         :code code
         :vat-no vat-no
         :registered-address registered-address
         :invoice-address (or invoice-address registered-address)
         :invoice-addressee invoice-addressee))

      (doseq [{:keys [entity type currency account-no sort-code]} accounts]
        (db/create-account!
         conn
         :entity entity
         :type type
         :currency currency
         :account-no account-no
         :sort-code sort-code
         ))

      (doseq [tx transactions]
        (cond
         (and (vector? tx) (= (first tx) :load))
         (load-transactions conn (assoc (second tx) :source fl))
         :otherwise
         (throw (ex-info "Don't know how to handle tx" {:tx tx}))))

      (doseq [{:keys [entity draw-from debit-to output-tax-rate output-tax-account invoice-date output-dir issue-date issuer receiving-account signatory purchase-order-reference] :as invoice-args} invoices]
        (let [db (d/db conn)
              company (d/entity db issuer)
              receiving-account (db/find-account db {:entity issuer :type receiving-account})
              accno (:pro.juxt.accounting/account-number receiving-account)
              sort-code (:pro.juxt.accounting/sort-code receiving-account)
              templater (invoicing/create-invoice-data-template
                         ;; TODO: All these fields are part of the issuer
                         {:title "Director"
                          :notes (str "Please make payment via BACS to our bank (account " accno ", sort-code " sort-code ") within 30 days.\nMany thanks.")
                          :signatory signatory
                          :company-name (:pro.juxt.accounting/name company)
                          :company-address (edn/read-string (:pro.juxt.accounting/registered-address company))
                          :vat-no (:pro.juxt.accounting/vat-number company)
                          :vat-rate output-tax-rate
                          :bank-account-no accno
                          :bank-sort-code sort-code
                          })
              code (:pro.juxt.accounting/code (d/entity db entity))
              invoice
              (invoicing/issue-invoice
               conn
               :draw-from (:db/id (db/find-account db {:entity entity :type draw-from}))
               :debit-to (:db/id (db/find-account db {:entity entity :type debit-to}))
               :output-tax-account (:db/id (db/find-account db output-tax-account))
               :output-tax-rate output-tax-rate
               :invoice-date invoice-date
               :issue-date issue-date
               :invoice-ref-prefix (format "%s-%s-" code (.getYear (DateTime. invoice-date)))
               :initial-invoice-suffix "01"
               :purchase-order-reference purchase-order-reference)
              invoice-args (update-in invoice-args [:output-dir]
                                      #(.getAbsolutePath (File. (.getParentFile fl) %)))
              invoice-data (templater (d/db conn) invoice invoice-args)]
          (pprint invoice-data)
          (invoicing/generate-pdf-for-invoice invoice-data)
          )))))
