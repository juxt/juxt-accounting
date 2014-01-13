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
(ns juxt.accounting.ofx-test
  (:require
   [clojure.test :refer :all]
   [juxt.accounting.ofx :refer :all]
   [clojure.java.io :as io]
   [clojure.set :refer (union)]))

(deftest foo
  (is (= 4 (+ 2 2))))

#_(let [dir (io/file "/home/malcolm/Dropbox.private/JUXT/financials/natwest")]
  (distinct (map :name (second (first (apply merge-with union (map extract-transactions (.listFiles dir)))))))
  )

#_(let [dir (io/file "/home/malcolm/Dropbox.private/JUXT/financials/natwest")]
  (apply union (map second (apply merge-with union (map extract-transactions (.listFiles dir)))))
  )

#_(let [dir (io/file "/home/malcolm/Dropbox.private/JUXT/financials/natwest")]
  (apply merge-with union (map extract-transactions (.listFiles dir)))
  )
