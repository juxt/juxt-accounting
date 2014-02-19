{:jig/components
 {
  :accounts/stencil-loader
  {:jig/component jig.stencil/StencilLoader
   :jig/project #=(eval (str (System/getProperty "user.home") "/src/accounting/project.clj"))}

  :accounts/db
  {:jig/component juxt.accounting.database/Database
   :jig/project #=(eval (str (System/getProperty "user.home") "/src/accounting/project.clj"))
   :db {:uri "datomic:mem://juxt/accounts"}
   }

  :accounts/static-loader
  {:jig/component juxt.accounting.static/StaticLoader
   :jig/project #=(eval (str (System/getProperty "user.home") "/src/accounting/project.clj"))
   :jig/dependencies [:accounts/db]
   ;; Contribute :static-file
   }

  :accounts/web
  {:jig/component juxt.accounting.web/Website
   :jig/project #=(eval (str (System/getProperty "user.home") "/src/accounting/project.clj"))
   :jig/dependencies [:accounts/stencil-loader]
   :jig.stencil/loader :accounts/stencil-loader
   :juxt.accounting/data :accounts/db
   :bootstrap-dist #=(eval (str (System/getProperty "user.home") "/src/bootstrap/dist"))
   :jquery-dist #=(eval (str (System/getProperty "user.home") "/src/jquery/dist"))
   }

  :accounts/entities-view
  {:jig/component juxt.accounting.entities/Entities
   :jig/project #=(eval (str (System/getProperty "user.home") "/src/accounting/project.clj"))
   :jig/dependencies [:accounts/web]
   }

  :accounts/routing
  {:jig/component jig.bidi/Router
   :jig/project #=(eval (str (System/getProperty "user.home") "/src/accounting/project.clj"))
   :jig/dependencies [:accounts/web :accounts/entities-view]
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
   :jig/dependencies [:accounts/server :console/server :accounts/db #_:accounts/statement-processor]
   :jig.web.firefox-reload/host "localhost"
   :jig.web.firefox-reload/port 32000}}

 }
