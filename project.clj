(defproject pro.juxt/accounting "0.1.5-SNAPSHOT"
  :description "JUXT Accounting"
  :url "https://juxt.pro/accounting"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 ;; Configuration
                 [jarohen/nomad "0.3.1"]
                 ;; Logging
                 [org.clojure/tools.logging "0.2.6"]
;;                 [com.taoensso/timbre "2.1.2"]
                 [ch.qos.logback/logback-classic "1.0.7" :exclusions [org.slf4j/slf4j-api]]
                 [org.slf4j/jul-to-slf4j "1.7.2"]
                 [org.slf4j/jcl-over-slf4j "1.7.2"]
                 [org.slf4j/log4j-over-slf4j "1.7.2"]
                 ;; Database
                 [com.datomic/datomic-free "0.8.4007"]
                 ;; Time!
                 [clj-time "0.5.1"]
                 ;; Money!
                 [clojurewerkz/money "1.3.0"]
                 ;; Printing
                 [clj-pdf "1.10.0"]
                 ;; Pedestal! (with jetty)
                 [io.pedestal/pedestal.service "0.1.10"]
                 [io.pedestal/pedestal.jetty "0.1.10"]
                 ;; Hiccup for HTML generation
                 [hiccup "1.0.4"]
                 ;; CSS
                 [garden "0.1.0-beta6"]
                 ]

  :repl-options {:host "127.0.0.1"
                 :port 9797
                 :prompt (fn [ns] (str "JUXT Accounting: [" ns "]> "))}

  :profiles {:dev {:source-paths ["dev"]
                   :dependencies [[org.clojure/tools.namespace "0.2.4"]
                                  [org.clojure/java.classpath "0.2.0"]]}}

  :min-lein-version "2.0.0"
  )
