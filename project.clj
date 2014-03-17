;; Copyright © 2013 - 2014, JUXT LTD. All Rights Reserved.
;;
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;;
;; By using this software in any fashion, you are agreeing to be bound by the
;; terms of this license.
;;
;; You must not remove this notice, or any other, from this software.

(def jig-version "2.0.3")

(defproject juxt/accounting "0.1.5-SNAPSHOT"
  :description "JUXT Accounting"
  :url "https://juxt.pro/accounting"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies
  [[org.clojure/clojure "1.5.1"]
   ;; Logging
   [org.clojure/tools.logging "0.2.6"]

   [ch.qos.logback/logback-classic "1.0.7" :exclusions [org.slf4j/slf4j-api]]
   [org.slf4j/jul-to-slf4j "1.7.2"]
   [org.slf4j/jcl-over-slf4j "1.7.2"]
   [org.slf4j/log4j-over-slf4j "1.7.2"]

   ;; Database
   [juxt/datomic-extras "1.0.3"
    :exclusions [org.slf4j/slf4j-nop
                 org.slf4j/jul-to-slf4j
                 org.slf4j/jcl-over-slf4j
                 org.slf4j/log4j-over-slf4j]]

   ;; EDN reader with location metadata
   [org.clojure/tools.reader "0.8.3"]

   ;; Time!
   [clj-time "0.5.1"]
   ;; Money!
   [clojurewerkz/money "1.4.0"]
   ;; Printing
   [clj-pdf "1.10.0"]
   ;; Hiccup for HTML generation
   [hiccup "1.0.4"]
   ;; CSS
   [garden "0.1.0-beta6"]
   ;; Instaparse and zippers for parsing OFX
   [instaparse "1.2.4"]
   [org.clojure/data.zip "0.1.1"]
   [camel-snake-kebab "0.1.2"]
   ;; JTidy
   [jtidy "4aug2000r7-dev"]

   ;; Tracing
   [org.clojure/tools.trace "0.7.6"]

   ;; Jig
   [jig ~jig-version]
   [jig/bidi ~jig-version]

   [jig/stencil ~jig-version]
   [jig/http-kit ~jig-version]
   [jig/protocols ~jig-version]
   ]

  :profiles {:dev {:source-paths ["jig"]}}

  :min-lein-version "2.0.0"
  )
