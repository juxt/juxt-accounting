; Copyright © 2013, JUXT LTD. All Rights Reserved.
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
   [clojurewerkz.money.amounts :as ma :refer (amount-of)]
   [clojure.pprint :refer (pprint)]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.java.io :refer (file resource) :as io]
   [clojure.tools.logging :refer :all]
   [datomic.api :as d]
   [clj-time.core :as time]
   [clojurewerkz.money.currencies :as mc :refer (GBP)]
   [clojurewerkz.money.amounts :as ma]
   [pro.juxt.accounting
    [database :as db]
    [invoicing :as invoicing]])
  (:import
   (java.io File)
   (org.joda.time DateTime)
   (org.joda.money CurrencyUnit Money)))

(defn load-transactions [conn {:keys [loader input source format columns] :or {format "application/edn" columns []} :as args}]
  (require (symbol (namespace loader)))
  (let [ldr (ns-resolve (symbol (namespace loader)) (symbol (name loader)))
        db (d/db conn)]
    (when-not ldr (throw (ex-info "Failed to resolve loader" {:namespace (namespace loader)
                                                              :name (name loader)})))
    (let [billing-file (file (.getParentFile source) input)]
      (when-not (.exists billing-file) (throw (ex-info "Billing input file does not exist" {:billing-file billing-file})))
      (doseq [billing (case format
                        "application/edn" (edn/read-string (slurp billing-file))
                        "text/tab-separated-values" (map (partial zipmap columns) (map #(str/split % #"\t") (line-seq (io/reader billing-file)))))]
        (let [bookentry (ldr db args billing)] ; should ldr be called transformer?
          (when bookentry
            @(d/transact
              conn
              (let [txid (d/tempid :db.part/tx)]
                (concat
                 (for [[n v] (:metadata bookentry)]
                   [:db/add txid n v])
                 (db/assemble-transaction
                  db txid
                  (or (:date bookentry) (:date billing))
                  [(select-keys bookentry [:debit-account :credit-account :amount :description])]))))))))))

(defn process-accounts-file [path dburi]
  (let [fl (file path)
        {:keys [entities accounts transactions invoices vat-returns]}
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

         (map? tx)
         @(d/transact
           conn
           (let [txid (d/tempid :db.part/tx)
                 db (d/db conn)]
             (concat
              (list
               [:db/add txid :pro.juxt/description (:description tx)])
              (db/assemble-transaction
               db txid
               :date (:date tx)
               :debits {(db/to-ref-id (db/find-account db (:debit-account tx))) (amount-of (:currency tx) (:amount tx))}
               :credits {(db/to-ref-id (db/find-account db (:credit-account tx))) (amount-of (:currency tx) (:amount tx))}
               ))))

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
              ;; We need to reparent the value of output-dir relative to the file it is specified in.
              invoice-args (update-in invoice-args [:output-dir]
                                      #(.getAbsolutePath (File. (.getParentFile fl) %)))
              invoice-data (templater (d/db conn) invoice invoice-args)]
          (invoicing/generate-pdf-for-invoice conn invoice-data)
          ))

      #_(doseq [{:keys [entity vat-account credit-account frs-credit-account date frs-rate]} vat-returns]
        (let [get-time (fn [d] (.getTime d))]
          ;; Get invoices for last 3 month period that haven't already been paid
          (debugf "VAT return - date is %s" (.getTime (db/to-date date)))

          (debugf "All invoices")
          (debug (with-out-str (pprint (db/get-invoices (d/db conn)))))

          (debugf "Filtered invoices")
          ;; TODO Need to reduce over invoices, sum sub-total, sum total (for box 6)

          (let [{:keys [invoices subtotal total output-tax]}
                (reduce
                 (fn [s {:keys [invoice total subtotal output-tax]}]
                   (-> s
                       (update-in [:invoices] conj invoice)
                       (update-in [:total] + total)
                       (update-in [:output-tax] + output-tax)
                       (update-in [:subtotal] + subtotal)
                       ))
                 {:invoices []
                  :subtotal 0
                  :output-tax 0
                  :total 0}
                 (filter
                  (every-pred
                   (comp not :output-tax-paid)
                   (comp (partial > (get-time (db/to-date date))) get-time :invoice-date))
                  (db/get-invoices (d/db conn))))]

            (debugf "subtotal is %s" subtotal)
            (debugf "subtotal type is %s" (type subtotal))
            (debugf "frs-rate is %s" frs-rate)
            (debugf "frs-rate type is %s" (type frs-rate))
            ;; TODO: Check invoice is denominated in GBP first!
            (let [owing (-> (.multipliedBy
                             (amount-of GBP subtotal)
                             frs-rate
                             java.math.RoundingMode/DOWN)
                            (ma/round 0 :down))
                  keep (ma/minus (amount-of GBP output-tax) owing)]

              (debugf "owing is %s" owing)
              (debugf "keep is %s" keep)
              (let [db (d/db conn)]
                @(d/transact
                  conn
                  (let [returnid (d/tempid :db.part/user)
                        txid (d/tempid :db.part/tx)]
                    (concat
                     (for [invoice invoices]
                       [:db/add invoice :pro.juxt.accounting/output-tax-paid returnid])
                     (list
                      [:db/add txid :pro.juxt/description "VAT paid to HMRC"]
                      [:db/add txid :pro.juxt/description "VAT paid to HMRC"]
                      [:db/add returnid :pro.juxt.accounting/date date]
                      [:db/add returnid :pro.juxt.accounting.vat/box-6 total]
                      [:db/add returnid :pro.juxt.accounting.vat/box-1 (.getAmount owing)])
                     (db/assemble-transaction
                      db txid
                      :date (db/to-date date)
                      :debits {(db/find-account db {:entity entity :type vat-account})
                               (amount-of GBP output-tax)}
                      :credits {(db/find-account db {:entity entity :type credit-account})
                                owing
                                (db/find-account db {:entity entity :type frs-credit-account})
                                keep}))))))))))))
