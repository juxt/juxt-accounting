{:jig/components
 {
  :accounts/stencil-loader
  {:jig/component jig.stencil/StencilLoader
   :jig/project #=(eval (str (System/getProperty "user.home") "/src/accounting/project.clj"))}

  :accounts/db
  {:jig/component juxt.accounting.jig/Database
   :jig/project #=(eval
                   (str
                    (System/getProperty "user.home")
                    "/src/accounting/project.clj"))
   :db {:uri "datomic:mem://juxt/accounts"}
   }

  :accounts/data-extractor
  {:jig/component juxt.accounting.jig/DataExtractor
   :jig/project #=(eval
                   (str
                    (System/getProperty "user.home")
                    "/src/accounting/project.clj"))
   ;; Merge in :accounts-file, which needs processing
   }

  :accounts/data-loader
  {:jig/component juxt.accounting.jig/DataLoader
   :jig/project #=(eval
                   (str
                    (System/getProperty "user.home")
                    "/src/accounting/project.clj"))
   :jig/dependencies [:accounts/db :accounts/data-extractor]
   }

  :accounts/statement-processor
  {:jig/component juxt.accounting.jig/StatementProcessor
   :jig/project #=(eval (str (System/getProperty "user.home") "/src/accounting/project.clj"))
   :jig/dependencies [:accounts/data-loader]
   :database :accounts/db
   ;; Merge in :statement-directory, where downloaded bank statements can be found (in OFX format)
   }

  :accounts/service
  {:jig/component juxt.accounting.jig/Website
   :jig/project #=(eval
                   (str
                    (System/getProperty "user.home")
                    "/src/accounting/project.clj"))
   :jig/dependencies [:accounts/stencil-loader :accounts/statement-processor :accounts/data-loader :accounts/data-extractor]
   :jig.stencil/loader :accounts/stencil-loader

   :juxt.accounting/data :accounts/db

   :bootstrap-dist #=(eval (str (System/getProperty "user.home") "/src/bootstrap/dist"))
   :jquery-dist #=(eval (str (System/getProperty "user.home") "/src/jquery/dist"))
   }

  :accounts/routing
  {:jig/component jig.bidi/Router
   :jig/project #=(eval (str (System/getProperty "user.home") "/src/accounting/project.clj"))
   :jig/dependencies [:accounts/service]
   ;; Optionally, route systems can be mounted on a sub-context
   ;;:jig.web/context "/accounts"
   }

  :accounts/server
  {:jig/component jig.http-kit/Server
   :jig/project #=(eval (str (System/getProperty "user.home") "/src/accounting/project.clj"))
   :jig/dependencies [:accounts/routing]
   :port 8000}

  #_:firefox-reloader
  #_{:jig/component jig.web.firefox-reload/Component
   :jig/dependencies [:accounts/server :console/server :accounts/db :accounts/statement-processor]
   :jig.web.firefox-reload/host "localhost"
   :jig.web.firefox-reload/port 32000}}

 :jig/projects
 [{:jig/project #=(eval
                   (str
                    (System/getProperty "user.home")
                    "/src/accounting/project.clj"))}]}
