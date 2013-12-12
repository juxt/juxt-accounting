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

;; Right, I know this namespace is just awful, consisting of HUGE
;; functions with few if any comments. It's an early version and I'm
;; concentrating on getting things working rather than code style. I'll
;; re-factor it sometime, I promise, really I will....

(ns juxt.accounting.driver
  (:require
   [clojurewerkz.money.amounts :as ma :refer (amount-of)]
   [clojure.pprint :refer (pprint)]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.java.io :refer (file resource) :as io]
   [clojure.tools.logging :refer :all]
   [datomic.api :as d]
   [clj-time.core :as time]
   [clj-time.coerce :refer (from-date)]
   [clojurewerkz.money.currencies :as mc :refer (GBP to-currency-unit)]
   [clojurewerkz.money.amounts :as ma]
   [juxt.datomic.extras :refer (to-entity-map)]
   [juxt.accounting
    [database :as db]
    [invoicing :as invoicing]
    [money :refer (as-money)]]))

(defn process-accounts-file [path dburi]
  (let [fl (file path)
        {:keys [entities accounts transactions invoices vat-returns]}
        (edn/read-string
         {:readers {'juxt.accounting/currency
                    (fn [x] (to-currency-unit (str x)))}}
         (slurp fl))]

    (let [conn (d/connect dburi)]
      (doseq [[ident {:keys [name code vat-no registered-address invoice-address invoice-addressee client supplier]}] entities]
        (do
          (db/create-legal-entity!
           conn
           :ident ident
           :name name
           :code code
           :vat-no vat-no
           :registered-address registered-address
           :invoice-address (or invoice-address registered-address)
           :invoice-addressee invoice-addressee)
          (when client
            (db/create-account!
             conn
             :ident (keyword (clojure.core/name ident) "accounts-receivable")
             :entity ident
             :currency (to-currency-unit "GBP")
             :name "Accounts Receivable"
             )
            (db/create-account!
             conn
             :ident (keyword (clojure.core/name ident) "assets")
             :entity ident
             :currency (to-currency-unit "GBP")
             :name "Work supplied"
             )
            (db/create-account!
             conn
             :ident (keyword (clojure.core/name ident) "assets-pending-invoice")
             :entity ident
             :currency (to-currency-unit "GBP")
             :name "Billable work"
             ))
          (when supplier
            (db/create-account!
             conn
             :ident (keyword (clojure.core/name ident) "accounts-payable")
             :entity ident
             :currency (to-currency-unit "GBP")
             :name "Accounts Receivable"
             )
            (db/create-account!
             conn
             :ident (keyword (clojure.core/name ident) "liabilities")
             :entity ident
             :currency (to-currency-unit "GBP")
             :name "Billable work"
             ))))

      (doseq [{:keys [ident name entity currency account-no sort-code]} accounts]
        (db/create-account!
         conn
         :ident ident
         :name name
         :entity entity
         :currency currency
         :account-no account-no
         :sort-code sort-code
         ))

      (doseq [{:keys [credit-account debit-account items]} transactions]

        (let [db (d/db conn)]
          @(d/transact
            conn
            (apply concat
                   (for [{:keys [dates description unit-amount vat-rate]} items]
                     (apply concat
                            (for [date dates]
                              (db/assemble-transaction
                               (d/db conn)
                               date
                               (remove nil?
                                       [{:debit-account debit-account
                                         :credit-account credit-account
                                         :description description
                                         :amount unit-amount
                                         }])
                               "transaction loaded from file"))))))))

      (doseq [{:keys [entity draw-from debit-to output-tax-rate output-tax-account invoice-date output-dir issue-date issuer receiving-account signatory purchase-order-reference] :as invoice-args} invoices]
        (infof "Preparing invoice")
        (let [db (d/db conn)
              company (d/entity db issuer)
              {accno :juxt.accounting/account-number sort-code :juxt.accounting/sort-code} (to-entity-map receiving-account db)
              templater (invoicing/create-invoice-data-template
                         ;; TODO: All these fields are part of the issuer
                         {:title "Director"
                          :signatory signatory
                          :company-name (:juxt.accounting/name company)
                          :company-address (edn/read-string (:juxt.accounting/registered-address company))
                          :vat-no (:juxt.accounting/vat-number company)
                          :vat-rate output-tax-rate
                          :bank-account-no accno
                          :bank-sort-code sort-code
                          })
              code (:juxt.accounting/code (d/entity db entity))
              invoice
              (invoicing/issue-invoice
               conn
               :draw-from draw-from
               :debit-to debit-to
               :output-tax-account output-tax-account
               :output-tax-rate output-tax-rate
               :invoice-date invoice-date
               :issue-date issue-date
               :invoice-ref-prefix (format "%s-%s-" code (time/year (from-date invoice-date)))
               :initial-invoice-suffix "01"
               :purchase-order-reference purchase-order-reference)
              ;; We need to reparent the value of output-dir relative to the file it is specified in.
              invoice-args (update-in invoice-args [:output-dir]
                                      #(.getAbsolutePath (file (.getParentFile fl) %)))
              invoice-data (templater (d/db conn) invoice invoice-args)]
          (infof "Invoice data: %s" invoice-data)
          (invoicing/generate-pdf-for-invoice conn invoice-data)
          ))

      (doseq [{:keys [entity vat-account credit-account date retained-vat-account] :as vat-return} vat-returns]
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

            ;; TODO: Check invoice is denominated in GBP first!

            (let [owing (if-let [frs-rate (:frs-rate vat-return)]
                          (-> (.multipliedBy
                               (amount-of GBP subtotal)
                               frs-rate
                               java.math.RoundingMode/DOWN)
                              (ma/round 0 :down))
                          (-> (amount-of GBP output-tax)
                              (ma/round 0 :down)))
                  keep (ma/minus (amount-of GBP output-tax) owing)]

              (debugf "owing is %s" owing)
              (debugf "keep is %s" keep)

              (let [db (d/db conn)]
                @(d/transact
                  conn
                  (let [returnid (d/tempid :db.part/user)]
                    (concat
                     (for [invoice invoices]
                       [:db/add invoice :juxt.accounting/output-tax-paid returnid])
                     (list
                      [:db/add returnid :juxt.accounting/date date]

                      ;; Err. surely these is EXCLUDING VAT!! (for JUXT at least) but could be different for FRS
                      ;; Don't floor it either, it's meant to be in pounds and pence!
                      ;; Err, box 1 is in pounds and pence, box 6 in pounds only
                      [:db/add returnid :juxt.accounting.vat/box-6 (BigInteger/valueOf (long (Math/floor total)))]

                      [:db/add returnid :juxt.accounting.vat/box-1 (BigInteger/valueOf (long (.getAmount owing)))])

                     (db/assemble-transaction
                      db
                      (db/to-date date)
                      (let [a {:debit-account vat-account
                               :credit-account credit-account
                               :amount owing
                               :description "VAT"}]
                        (if (ma/positive? keep)
                          [a {:debit-account vat-account
                              :credit-account retained-vat-account
                              :amount keep
                              :description "Retained VAT"
                              }]
                          [a]))
                      "driver"))))))))))))
