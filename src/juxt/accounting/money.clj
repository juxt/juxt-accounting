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
(ns juxt.accounting.money
  (:require
   [clojurewerkz.money.amounts :refer (amount-of parse)]))

;; Coercions to money

(defprotocol Currency
  (as-currency [_]))

(extend-protocol Currency
  org.joda.money.CurrencyUnit
  (as-currency [cu] cu)
  String
  (as-currency [cu] (org.joda.money.CurrencyUnit/getInstance cu)))

(defprotocol Money
  (as-money [amount currency]))

(extend-protocol Money
  org.joda.money.Money
  (as-money [amount currency]
    (assert (= (.getCurrencyUnit amount) (as-currency currency)))
    amount)
  String
  (as-money [amount currency]
    (let [res (parse amount)]
      (assert (= (.getCurrencyUnit res) (as-currency currency)))
      res))

  BigDecimal
  (as-money [amount currency]
    (let [rounded (.setScale amount 2 java.math.RoundingMode/HALF_UP)]
      (org.joda.money.Money/of (as-currency currency) rounded)))

  Number
  (as-money [amount currency] (as-money (BigDecimal. (double amount)) currency)))
