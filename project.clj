(defproject pro.juxt/accounting "0.1.1"
  :description "JUXT Accounting"
  :url "https://juxt.pro/accounting"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 ;; Configuration
                 [jarohen/nomad "0.3.1"]
                 ;; Logging
                 [com.taoensso/timbre "2.1.2"]
                 ;; Database
                 [com.datomic/datomic-free "0.8.4007"]
                 ;; Time!
                 [clj-time "0.5.1"]
                 ;; Money!
                 [clojurewerkz/money "1.3.0"]
                 ]

  :repl-options {:host "127.0.0.1"
                 :port 9797
                 :prompt (fn [ns] (str "JUXT Accounting: [" ns "]> "))
                 :init-ns pro.juxt.accounting.repl
                 :init (do (require 'pro.juxt.accounting.repl)
                           (pro.juxt.accounting.repl/init))
                 :nrepl-middleware [pro.juxt.accounting.repl/bind-dburi]}

)
