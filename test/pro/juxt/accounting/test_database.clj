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
  (:require
   [clojure.test :refer :all]
   [pro.juxt.accounting.database :refer :all]
   [datomic.api :refer (q db delete-database entity) :as d]
   [taoensso.timbre :as timbre]
   ))

(defmacro with-temporary-database [tempuri & body]
  `(binding [*dburi* ~tempuri]
     (~delete-database ~tempuri)
     (~init ~tempuri)
     ~@body))

(defn using-temporary-database [tempuri]
  (fn [f] (with-temporary-database tempuri (f))))

(use-fixtures :each
  (using-temporary-database "datomic:mem://test-db")
  (fn [f]
    (timbre/set-level! :info)
    (f)))

(deftest test-create-account
  (let [conn (d/connect *dburi*)
        id (create-account! conn  "test")]
    (is (= "test" (:pro.juxt/name (entity (db conn) id))))
    (is (= "test" (get-name (db conn) id)))))
