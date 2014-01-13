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
(ns juxt.accounting.database-test
  "Testing the database functions."
  (:refer-clojure :exclude [zero? read-string])
  (:require
   [clojure.test :refer :all]
   [juxt.accounting.database :refer :all]
   [datomic.api :refer (q db delete-database entity ident transact tempid) :as d]
   [clojure.tools.logging :refer :all]
   [clojurewerkz.money.currencies :as mc :refer (GBP EUR)]
   [clojurewerkz.money.amounts :as ma :refer (amount-of zero?)]
   [clj-time.core :as time :refer (local-date)])
  (:import (org.joda.money Money CurrencyUnit)))

(def dburi "datomic:mem://test-db")

(defmacro with-temporary-database [tempuri & body]
  `(do
     (~delete-database ~tempuri)
     (~init ~tempuri)
     ~@body))

(defn using-temporary-database [tempuri]
  (fn [f] (with-temporary-database tempuri (f))))

(use-fixtures :each
  (fn [f]
    ;;(timbre/set-level! :info)
    (f))
  (using-temporary-database dburi))

(deftest test-create-legal-entity
  (let [conn (d/connect dburi)
        ent (create-legal-entity! conn :ident :test-client)]
    (is (= :test-client (:db/ident ent)))))

(deftest test-create-account
  (let [conn (d/connect dburi)
        owner (create-legal-entity! conn :ident :test-client)
        acc (create-account! conn
                             :parent :test-client
                             :ident :test-account
                             :currency EUR
                             :description "Just for testing")]
    (is (= :test-account (:db/ident acc)))
    (is (= "Just for testing" (:juxt/description acc)))))

#_(deftest test-create-transactions
  (let [conn (d/connect dburi)
        owner (create-legal-entity! conn :ident :test-client)
        client (create-account! conn :parent :test-client :ident :client-x :currency GBP)
        consultant (create-account! conn :ident :consultant-y :currency GBP)]
    @(transact conn
               (assemble-transaction
                (db conn)
                (tempid :db.part/tx)
                :debits {client (amount-of GBP 320)}
                :credits {consultant (amount-of GBP 320)}
                :date (local-date 2013 06 01)))
    @(transact conn
               (assemble-transaction
                (db conn)
                (tempid :db.part/tx)
                :debits {client (amount-of GBP 320)}
                :credits {consultant (amount-of GBP 320)}
                :date (local-date 2013 06 02)))
    @(transact conn
               (assemble-transaction
                (db conn)
                (tempid :db.part/tx)
                :debits {client (amount-of GBP 160)}
                :credits {consultant (amount-of GBP 160)}
                :date (local-date 2013 06 03)))
    (is (= (amount-of GBP 800) (get-balance (db conn) client)))
    (is (= (amount-of GBP 800) (get-total-debit (db conn) client)))
    (is (= (amount-of GBP -800) (get-balance (db conn) consultant)))
    (is (= (amount-of GBP 800) (get-total-credit (db conn) consultant)))
    (is (zero? (reconcile-accounts (db conn) client consultant)))))

(deftest test-illegal-transactions
  (let [conn (d/connect dburi)
        client (create-account! conn :ident :client-x :currency GBP)
        consultant (create-account! conn :ident :consultant-y :currency GBP)]
    (testing "Wrong currency"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"Entry amount is in a different currency to that of the account"
           @(transact conn
                      (assemble-transaction
                       (db conn)
                       (tempid :db.part/tx)
                       :debits {client (amount-of EUR 320)}
                       :credits {consultant (amount-of GBP 320)}
                       :date (local-date 2013 06 01))))))
    (testing "Credits and debits don't balance"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Debits do not balance with credits"
                            @(transact conn
                                      (assemble-transaction
                                       (db conn)
                                       (tempid :db.part/tx)
                                       :debits {client (amount-of GBP 320)}
                                       :credits {consultant (amount-of GBP 300)}
                                       :date (local-date 2013 06 01))))))))

(deftest test-multifx-transaction
  (let [conn (d/connect dburi)
        client (create-account! conn :ident :client-x :currency EUR)
        consultant (create-account! conn :ident :consultant-y :currency GBP)]
    (transact conn
              (assemble-transaction
               (db conn)
               (tempid :db.part/tx)
               :debits {client (amount-of EUR 400)}
               :credits {consultant (amount-of GBP 300)}
               :date (local-date 2013 06 01)))))
