;; Copyright © 2013, JUXT Ltd. All Rights Reserved.
;;
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;;
;; By using this software in any fashion, you are agreeing to be bound by the
;; terms of this license.
;;
;; You must not remove this notice, or any other, from this software.
(ns pro.juxt.accounting.test-database
  "Testing the database functions."
  (:refer-clojure :exclude [zero?])
  (:require
   [clojure.test :refer :all]
   [pro.juxt.accounting.database :refer :all]
   [datomic.api :refer (q db delete-database entity) :as d]
   [taoensso.timbre :as timbre]
   [clojurewerkz.money.currencies :as mc :refer (GBP EUR)]
   [clojurewerkz.money.amounts :as ma :refer (amount-of zero?)]
   [clj-time.core :as time :refer (local-date)])
  (:import (org.joda.money Money CurrencyUnit)))

(defmacro with-temporary-database [tempuri & body]
  `(binding [*dburi* ~tempuri]
     (~delete-database ~tempuri)
     (~init ~tempuri)
     ~@body))

(defn using-temporary-database [tempuri]
  (fn [f] (with-temporary-database tempuri (f))))

(use-fixtures :each
  (fn [f]
    (timbre/set-level! :info)
    (f))
  (using-temporary-database "datomic:mem://test-db"))

(deftest test-create-account
  (let [conn (d/connect *dburi*)
        id (create-account! conn "test" EUR :description "test account")
        db (db conn)]
    (is (= "test" (:pro.juxt/name (entity db id))))
    (is (= "test" (get-name db id)))
    (is (= "test account" (get-description db id)))))

(deftest test-create-transactions
  (let [conn (d/connect *dburi*)
        client (create-account! conn "Client X" GBP)
        consultant (create-account! conn "Consultant Y" GBP)]
    (create-entry! conn
                  {client (amount-of GBP 320)}
                  {consultant (amount-of GBP 320)}
                  :instance-of :pro.juxt.accounting.standard-transactions/consulting-full-day
                  :date (local-date 2013 06 01))
    (create-entry! conn
                  {client (amount-of GBP 320)}
                  {consultant (amount-of GBP 320)}
                  :instance-of :pro.juxt.accounting.standard-transactions/consulting-full-day
                  :date (local-date 2013 06 02))
    (create-entry! conn
                  {client (amount-of GBP 160)}
                  {consultant (amount-of GBP 160)}
                  :instance-of :pro.juxt.accounting.standard-transactions/consulting-half-day
                  :date (local-date 2013 06 03))
    (is (= (amount-of GBP 800) (get-balance (db conn) client)))
    (is (= (amount-of GBP 800) (get-total-debit (db conn) client)))
    (is (= (amount-of GBP -800) (get-balance (db conn) consultant)))
    (is (= (amount-of GBP 800) (get-total-credit (db conn) consultant)))
    (is (zero? (reconcile-accounts (db conn) client consultant)))))

(deftest test-illegal-transactions
  (let [conn (d/connect *dburi*)
        client (create-account! conn "Client X" GBP)
        consultant (create-account! conn "Consultant Y" GBP)]
    (testing "Wrong currency"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Entry amount is in a different currency to that of the account"
                   (create-entry! conn
                                 {client (amount-of EUR 320)}
                                 {consultant (amount-of GBP 320)}
                                 :instance-of :pro.juxt.accounting.standard-transactions/consulting-full-day
                                 :date (local-date 2013 06 01)))))
    (testing "Credits and debits don't balance"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Debits do not balance with credits"
                   (create-entry! conn
                                 {client (amount-of GBP 320)}
                                 {consultant (amount-of GBP 300)}
                                 :instance-of :pro.juxt.accounting.standard-transactions/consulting-full-day
                                 :date (local-date 2013 06 01)))))))

(deftest test-multifx-transaction
  (let [conn (d/connect *dburi*)
        client (create-account! conn "Client X" EUR)
        consultant (create-account! conn "Consultant Y" GBP)]
    (create-entry! conn
                  {client (amount-of EUR 400)}
                  {consultant (amount-of GBP 300)}
                  :instance-of :pro.juxt.accounting.standard-transactions/consulting-full-day
                  :date (local-date 2013 06 01))))
