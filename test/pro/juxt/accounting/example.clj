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
(ns pro.juxt.accounting.example
  (:refer-clojure :exclude [zero?])
  (:require
   [pro.juxt.accounting
    [database :as db]
    [invoicing :as invoicing]]
   [datomic.api :as d]
   [clojurewerkz.money.amounts :as ma :refer (amount-of zero? total)]
   [clojurewerkz.money.currencies :as mc :refer (GBP EUR)]
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [clj-time.core :as time]
   [clj-time.format :as timeformat])
  (:import (org.joda.money Money CurrencyUnit)
           (org.joda.time DateTime)))

(defn create-database [dburi]
  (d/delete-database dburi)
  (db/init dburi))

(defn create-static [conn]
  (db/create-legal-entity! conn
                           :ident :client
                           :principal "Gill Armstrong"
                           :name "Space Enterprises"
                           :postal-address ["1 Spaceport Road" "North Ridge" "Mars"])

  (db/create-legal-entity! conn
                           :ident :acme
                           :name "ACME LTD."
                           :postal-address ["1 Widget Way." "Bampton," "Hartleshire"])

  (db/create-account! conn :parent :client :ident :client-worked :currency GBP)
  (db/create-account! conn :parent :client :ident :client-invoiced :currency GBP)
  (db/create-account! conn :parent :client :ident :subcontractor-worked :currency GBP)

  ;; Output tax is VAT, incurred upon invoice.
  (db/create-account! conn :parent :juxt :ident :output-vat :currency GBP))


(defn invoice []
  (let [dburi "datomic:mem://com.acme/accounts"
        _ (create-database dburi)
        conn (d/connect dburi)]

    ;; Create accounts
    (create-static conn)

    ;; Add transaction
    @(d/transact conn
                 (let [txid (d/tempid :db.part/tx)]
                   (cons [:db/add txid :pro.juxt/description "Work done"]
                         (db/assemble-transaction
                          (d/db conn) txid
                          :date (java.util.Date.)
                          :debits {:client-worked (amount-of GBP 1000)}
                          :credits {:subcontract-worked (amount-of GBP 1000)}
                          ))))

    ;; Issue and print invoice
    (let [accno "12345678"
          sortcode "12-34-56"
          printer (invoicing/create-invoice-pdf-template
                   {:title "Director"
                    :notes (str "Please make payment via BACS to our bank (account " accno ", sort-code " sortcode ") within 30 days.\nMany thanks.")
                    :signatory "John Smith"
                    :company-name "ACME LTD."
                    :company-address  ["1 Widget Way." "Bampton," "Hartleshire"]
                    :vat-no "123 4567 89"
                    :bank-account-no accno
                    :bank-sort-code sortcode})]

      (-> (invoicing/issue-invoice conn
                                   :draw-from :client-worked
                                   :debit-to :client-invoiced
                                   :output-tax-account :output-vat
                                   :until (.getTime (java.util.Date.))
                                   :invoice-ref-prefix "MARS-2013-"
                                   :initial-invoice-suffix "01")
          (printer (d/db conn))))))
