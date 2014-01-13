;; -*- mode: Clojure; eval: (clojure-test-mode 1); -*-
;;
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
(ns juxt.accounting.spider-test
  (:use clojure.test)
  (:require [juxt.datomic.extras.spider :refer (spider)]))

(deftest paths
  (are [row mapping => result]
       (= result (spider row mapping))

       {:P "A"} {:Q [:P]} => {:Q "A"}
       {:P "A" :B "B"} {:Q [:P]} => {:Q "A"}
       {:P "ABC"} {:Q [:P count]} => {:Q 3}

       {:P {:a {:b "B"}}} {:Q [:P :a :b]} => {:Q "B"}

       {:P {:a ["A" 4 2 "C" 9]}} {:Q [:P :a #(filter number? %) vec]} => {:Q [4 2 9]}

       {:P {:a [{:b "A" :s true}{:b "B" :s false}{:b "C" :s true}]}}
       {:Q [:P :a (partial filter :s) (partial map :b) vec]} => {:Q ["A" "C"]}

       ;; The equivalent
       {:P {:a [{:b "Apple" :s true}
                {:b "Banana" :s false}
                {:b "Chive" :s true}
                {:b "Date" :s true}]}}
       {:Q [:P :a [:s] (partial map :b) (partial map count)]} => {:Q [5 5 4]}))
