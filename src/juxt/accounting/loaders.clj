;; Copyright © 2014, JUXT LTD. All Rights Reserved.
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
(ns juxt.accounting.loaders
  (:refer-clojure :exclude [read *data-readers*])
  (:require
   jig
   [datomic.api :as d]
   [clojure.java.io :as io]
   [juxt.datomic.extras :refer (to-entity-map)]
   [clojure.tools.reader :refer (read *data-readers*)]
   [clojure.tools.reader.reader-types :refer (indexing-push-back-reader source-logging-push-back-reader)]
   [clojure.edn :as edn]
   [clj-time.core :as time]
   [clj-time.coerce :refer (from-date)]
   [clojure.pprint :refer (pprint)]
   [clojure.tools.logging :refer :all]
   [clojurewerkz.money.currencies :as mc :refer (GBP to-currency-unit)]
   [juxt.accounting.database :as db]
   [juxt.accounting.invoicing :as invoicing]
   )
  (:import (jig Lifecycle))
  )

(defn add-consulting-rate-transations [dburi txfile transactions]
  (let [conn (d/connect dburi)
        db (d/db conn)]
    (doseq [{:keys [credit-account debit-account codes items]} transactions]
      (assert credit-account "Transaction range must have a credit account")
      (assert debit-account "Transaction range must have a debit account")
      (assert codes "Transaction range must have codes")
      (doseq [{:keys [date code expenses note units] :as item} items]
        (assert code (format "Every item must have a code, date is %s" date))
        (assert (get-in codes [code :rate]) (format "Failed to find rate for code: %s" code))
        @(d/transact
          conn
          (db/assemble-transaction
           db
           date
           (concat
            ;; Work
            [{:debit-account debit-account
              :credit-account credit-account
              :description (str (get-in codes [code :description])
                                (when note (str " (" note ")")))
              :amount (* (get-in codes [code :rate]) (or units 1))
              }])
           (format "transaction loaded from line %s of %s" (:line (meta item)) txfile)))
        (doseq [{:keys [description cost] :as expense} expenses]
          @(d/transact
            conn
            (db/assemble-transaction
             db
             date
             (concat
              ;; Expenses
              [{:debit-account debit-account
                :credit-account credit-account
                :description description
                :amount cost}]
              )
             (format "expense loaded from line %s of %s" (:line (meta expense)) txfile))))))))

(defn issue-invoices [dburi invoices]
  (doseq [{:keys [entity draw-from debit-to vat-rate vat-account invoice-date output-dir issue-date issuer receiving-account signatory purchase-order-reference] :as invoice-args} invoices]
    (debugf "Preparing invoice")
    (let [conn (d/connect dburi)
          db (d/db conn)
          company (d/entity db issuer)
          {accno :juxt.accounting/account-number sort-code :juxt.accounting/sort-code} (to-entity-map receiving-account db)

          code (:juxt.accounting/code (d/entity db entity))
          invoice
          (invoicing/issue-invoice
           conn
           :draw-from draw-from
           :debit-to debit-to
           :vat-account vat-account
           :vat-rate vat-rate
           :invoice-date invoice-date
           :issue-date issue-date
           :invoice-ref-prefix (format "%s-%s-" code (time/year (from-date invoice-date)))
           :initial-invoice-suffix "01"
           :purchase-order-reference purchase-order-reference)

          templater (invoicing/create-invoice-data-template
                     ;; TODO: All these fields are part of the issuer
                     {:title "Director"
                      :signatory signatory
                      :company-name (:juxt.accounting/name company)
                      :company-address (edn/read-string (:juxt.accounting/registered-address company))
                      :vat-no (:juxt.accounting/vat-number company)
                      :vat-rate vat-rate
                      :bank-account-no accno
                      :bank-sort-code sort-code
                      })
          invoice-data (templater (d/db conn) invoice invoice-args)]
      (infof "Invoice data: %s" invoice-data)
      (infof "First item is %s" (keys (:juxt.accounting/invoice-item-component (first (:items invoice-data)))
                                      ))
      (invoicing/generate-pdf-for-invoice conn invoice-data)
      )))

(deftype ConsultingRateLoader [config]
  Lifecycle
  (init [_ system] system)
  (start [_ system]
    (assert (:dburi system))
    (assert (:transaction-file config) "No transaction file")
    (let [txfile (io/file (:transaction-file config))
          _ (assert (.exists txfile) (format "Transaction file doesn't exist: %s" txfile))
          _ (assert (.isFile txfile) (format "Transaction exists but is not a file: %s" txfile))

          txfile-content
          (binding [*data-readers* {'juxt.accounting/currency (fn [x] (to-currency-unit (str x)))}]
            (read
             (indexing-push-back-reader
              (java.io.PushbackReader. (io/reader txfile))
              )))]

      (add-consulting-rate-transations
       (:dburi system)
       txfile
       (:transactions txfile-content))

      (issue-invoices (:dburi system) (:invoices txfile-content))

      system))

  (stop [_ system] system))


(defn add-fixed-rate-transations [dburi txfile transactions]
  (let [conn (d/connect dburi)
        db (d/db conn)]
    (doseq [{:keys [credit-account debit-account date description amount] :as tx} transactions]
      (assert credit-account "Transaction range must have a credit account")
      (assert debit-account "Transaction range must have a debit account")
      @(d/transact
          conn
          (db/assemble-transaction
              db
              date
              (concat
               ;; Work
               [{:debit-account debit-account
                 :credit-account credit-account
                 :description description
                 :amount amount
                 }])
              (format "transaction loaded from line %s of %s" (:line (meta tx)) txfile))))))

(deftype FixedPriceLoader [config]
  Lifecycle
  (init [_ system] system)
  (start [_ system]
    (assert (:dburi system))
    (assert (:transaction-file config) "No transaction file")
    (let [txfile (io/file (:transaction-file config))
          _ (assert (.exists txfile) (format "Transaction file doesn't exist: %s" txfile))
          _ (assert (.isFile txfile) (format "Transaction exists but is not a file: %s" txfile))

          txfile-content
          (binding [*data-readers* {'juxt.accounting/currency (fn [x] (to-currency-unit (str x)))}]
            (read
             (indexing-push-back-reader
              (java.io.PushbackReader. (io/reader txfile))
              )))]

      (add-fixed-rate-transations
       (:dburi system)
       txfile
       (:transactions txfile-content))

      (issue-invoices (:dburi system) (:invoices txfile-content))


      system))

  (stop [_ system] system))


(deftype Invoicer [config]
  Lifecycle
  (init [_ system] system)
  (start [_ system]
    (assert (:dburi system))
    (assert (:transaction-file config) "No transaction file")
    (let [txfile (io/file (:transaction-file config))
          _ (assert (.exists txfile) (format "Transaction file doesn't exist: %s" txfile))
          _ (assert (.isFile txfile) (format "Transaction exists but is not a file: %s" txfile))

          txfile-content
          (binding [*data-readers* {'juxt.accounting/currency (fn [x] (to-currency-unit (str x)))}]
            (read
             (indexing-push-back-reader
              (java.io.PushbackReader. (io/reader txfile))
              )))]

      (issue-invoices (:dburi system) txfile-content)

      system))

  (stop [_ system] system))

(defn add-payable-invoices [dburi txfile transactions]
  (let [conn (d/connect dburi)
        db (d/db conn)]
    (doseq [{:keys [date credit-account debit-account vat-account description subtotal vat total] :as tx} transactions]
      (assert credit-account "Transaction must have a credit account")
      (assert debit-account "Transaction must have a debit account")
      (assert vat-account "Transaction must have a VAT account")
      @(d/transact
          conn
          (db/assemble-transaction
              db
              date
              (concat
               ;; Work
               [{:debit-account debit-account
                 :credit-account credit-account
                 :description description
                 :amount subtotal
                 :component-type :net
                 }
                {:debit-account vat-account
                 :credit-account credit-account
                 :description (str "VAT on " description)
                 :amount vat
                 :component-type :vat
                 }])
              (format "Invoice payable, line %s of %s" (:line (meta tx)) txfile))))))

(deftype InvoicesPayableLoader [config]
  Lifecycle
  (init [_ system] system)
  (start [_ system]
    (assert (:dburi system))
    (assert (:transaction-file config) "No transaction file")
    (let [txfile (io/file (:transaction-file config))
          _ (assert (.exists txfile) (format "Transaction file doesn't exist: %s" txfile))
          _ (assert (.isFile txfile) (format "Transaction exists but is not a file: %s" txfile))

          txfile-content
          (binding [*data-readers* {'juxt.accounting/currency (fn [x] (to-currency-unit (str x)))}]
            (read
             (indexing-push-back-reader
              (java.io.PushbackReader. (io/reader txfile))
              )))]

      (add-payable-invoices
       (:dburi system)
       txfile
       txfile-content)

      system))

  (stop [_ system] system))
