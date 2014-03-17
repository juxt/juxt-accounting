(ns juxt.accounting.time
  (:require
   [clj-time.core :as coretime]
   [clj-time.coerce :refer (from-date to-date)]
   [clj-time.periodic :refer (periodic-seq)]
   [clj-time.format :as timeformat]))

(defprotocol CoerceLocalDate
  (to-local-date [_]))

(extend-protocol CoerceLocalDate
  java.util.Date
  (to-local-date [this] (to-local-date (from-date this)))
  org.joda.time.DateTime
  (to-local-date [this] (apply coretime/local-date ((juxt coretime/year coretime/month coretime/day) this)))
  org.joda.time.LocalDate
  (to-local-date [this] this)
  nil
  (to-local-date [_] nil))
