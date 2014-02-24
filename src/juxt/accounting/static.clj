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

(ns juxt.accounting.static
  (:require
   jig
   [datomic.api :as d]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [juxt.accounting.database :as db]
   [clojurewerkz.money.currencies :as mc :refer (GBP to-currency-unit)])
  (:import (jig Lifecycle)))

(defn process-static-file [{:keys [entities accounts]} dburi]
  (let [conn (d/connect dburi)]
    (doseq [[ident {:keys [name code vat-no registered-address
                           invoice-address invoice-addressee client supplier]}] entities]
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
              :ident (keyword (str (clojure.core/name ident) ".accounts-receivable"))
              :entity ident
              :currency (to-currency-unit "GBP")
              :name "Accounts Receivable")
          (db/create-account!
              conn
              :ident (keyword (str (clojure.core/name ident) ".assets-pending-invoice"))
              :entity ident
              :currency (to-currency-unit "GBP")
              :name "Billable work"))
        (when supplier
          (db/create-account!
              conn
              :ident (keyword (str (clojure.core/name ident) ".accounts-payable"))
              :entity ident
              :currency (to-currency-unit "GBP")
              :name "Accounts Receivable")
          (db/create-account!
              conn
              :ident (keyword (str (clojure.core/name ident) ".liabilities"))
              :entity ident
              :currency (to-currency-unit "GBP")
              :name "Billable work"))))

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

    ))

(deftype StaticLoader [config]
  Lifecycle
  (init [_ system] system)
  (start [_ system]
    (let [dburi (:dburi system)
          _ (when-not dburi (ex-info "No dburi" {}))]
      (assert (:static-file config) "No static file")
      (process-static-file
       (edn/read-string
        {:readers {'juxt.accounting/currency
                   (fn [x] (to-currency-unit (str x)))}}
        (slurp (io/file (:static-file config)))) dburi)
      system))
  (stop [_ system] system))
