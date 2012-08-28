(ns pallet.crate.cinderella
  "Crates for cinderella installation and configuration.

https://github.com/cinderella/cinderella"
  (:require
   [clojure.string :as string]
   [clojure.tools.logging :as logging]
   [pallet.action :as action]
   [pallet.parameter :as parameter]
   [pallet.script :as script]
   [pallet.script.lib :as lib]
   [pallet.session :as session]
   [pallet.stevedore :as stevedore]
   [clojure.string :as string])
  (:use
   [pallet.action :only [with-action-options]]
   [pallet.action.directory :only [directory]]
   [pallet.action.exec-script :only [exec-checked-script]]
   [pallet.action.package
    :only [package package-source package-manager* install-deb]]
   [pallet.action.package.jpackage :only [add-jpackage]]
   [pallet.action.remote-directory :only [remote-directory]]
   [pallet.action.remote-file :only [remote-file]]
   [pallet.common.context :only [throw-map]]
   [pallet.compute :only [os-hierarchy]]
   [pallet.config-file.format :only [name-values]]
   [pallet.core :only [server-spec]]
   [pallet.crate.jetty :only [deploy]]
   [pallet.parameter :only [assoc-target-settings get-target-settings]]
   [pallet.phase :only [phase-fn]]
   [pallet.session :only [target-ip]]
   [pallet.thread-expr :only [when-> apply-map->]]
   [pallet.utils :only [apply-map]]
   [pallet.version-dispatch
    :only [defmulti-version-crate defmulti-version defmulti-os-crate
           multi-version-session-method multi-version-method
           multi-os-session-method]]
   [pallet.versions :only [version-string as-version-vector]]))

;;; ## cinderella install

(def ^:dynamic repo-url
  (str "https://repository-cinderella.forge.cloudbees.com"
       "/snapshot/io/cinderella/cinderella-web/%1$s/"))

(def ^:dynamic *cinderella-defaults*
  {:version "1.0-SNAPSHOT"
   :home "/usr/local/cinderella"
   ;; :backend-identity identity for backend service
   ;; :backend-credential credential for backend service
   :ec2-port 8080
   :ec2-version "2009-10-31"
   ;; default identity for frontend
   :identity "MvndHwA4e6dgaGV23L94"
   :credential "A50GS9tj2DLXRln4rf1K+A/CSjmAbBGw0H5yul6s"})

;;; Based on supplied settings, decide which install strategy we are using
;;; for cinderella.

(defmulti-version-crate cinderella-version-settings [version session settings])

(multi-version-session-method
    cinderella-version-settings {:os :linux}
    [os os-version version session settings]
  (cond
    (:strategy settings) settings
    (:jetty-war settings) (assoc settings :strategy :jetty-war)
    :else (let [url (format repo-url (:version settings))]
            (assoc settings
              :strategy :jetty-war
              :jetty-war {:url (str url
                                    (stevedore/script
                                     @(pipe (curl --silent ~url)
                                            (grep href)
                                            (grep war)
                                            (sed "'s/^.*<a href=\"//'")
                                            (sed "'s/\".*$//'")
                                            (tail -1))))}))))

;;; ## Settings
(defn- settings-map
  "Dispatch to either openjdk or oracle settings"
  [session settings]
  (cinderella-version-settings
   session
   (as-version-vector (string/replace (:version settings) "-SNAPSHOT" ""))
   (merge *cinderella-defaults* settings)))

(defn cinderella-settings
  "Capture settings for cinderella

:version
:home
:ec2-port
:ec2-version
:identity
:credential
:backend-identity
:backend-credential

:jetty-war"
  [session {:keys [version user home ec2-port ec2-version war instance-id]
            :or {version (:version *cinderella-defaults*)}
            :as settings}]
  (let [{:keys [backend-identity backend-credential] :as settings}
        (settings-map session (merge {:version version} settings))]
    (when-not (and backend-identity backend-credential)
      (throw-map
       "Attempt to install cinderella without specifying backend authorisation"
       {:message "Attempt to install cinderella without specifying backend auth"
        :backend-identity backend-credential
        :backend-credential (and backend-credential
                                 (string/replace backend-credential #"." "x"))
        :type :invalid-operation}))
    (assoc-target-settings session :cinderella instance-id settings)))

;;; # Install

;;; Dispatch to install strategy
(defmulti install-method (fn [session settings] (:strategy settings)))
(defmethod install-method :jetty-war
  [session {:keys [jetty-war]}]
  (apply-map deploy session "ROOT" jetty-war))

(defn install-cinderella
  "Install cinderella. By default will install as a war into jetty."
  [session & {:keys [instance-id]}]
  (let [settings (get-target-settings session :cinderella instance-id)]
    (logging/debugf "install-cinderella settings %s" settings)
    (if (= settings ::no-settings)
      (throw-map
       "Attempt to install cinderella without specifying settings"
       {:message "Attempt to install cinderella without specifying settings"
        :type :invalid-operation})
      (install-method session settings))))

;;; # Configure
(defn configure-cinderella
  [session & {:keys [instance-id]}]
  (let [{:keys [home backend-identity backend-credential backend-endpoint
                identity credential ec2-port ec2-version] :as settings}
        (get-target-settings session :cinderella instance-id)]
    (->
     session
     (directory home)
     (remote-file
      (str home "/ec2-service.properties")
      :content (name-values
                {:endpoint backend-endpoint
                 :useratorg backend-identity
                 :password backend-credential
                 :WSDLVersion ec2-version
                 (str "key." identity) credential}
                :separator "=")
      :literal true))))

(defn cinderella
  "Returns a service-spec for installing cinderella."
  [settings]
  (server-spec
   :phases {:settings (phase-fn
                        (cinderella-settings settings))
            :configure (phase-fn
                         (install-cinderella)
                         (configure-cinderella))}))

(defn compute-service-details
  "Return a map with the service details"
  [^org.jclouds.compute.ComputeService compute-service]
  (let [context (.. compute-service getContext unwrap)
        credential (.. context utils injector
                       (getInstance
                        (com.google.inject.Key/get
                         java.lang.String
                         org.jclouds.rest.annotations.Credential)))]
    {:identity (.getIdentity context)
     :credential credential
     :endpoint (.. context getProviderMetadata getEndpoint)}))
