(ns pallet.crate.cinderella-test
  (:use
   [org.jclouds.compute2 :only [compute-service nodes]]
   [pallet.action :only [def-clj-action]]
   [pallet.node :only [primary-ip]]
   [pallet.parameter :only [get-target-settings]]
   [pallet.parameter-test :only [settings-test]]
   [pallet.session :only [nodes-in-group]]
   clojure.test
   pallet.test-utils)
  (:require
   [clojure.tools.logging :as logging]
   [pallet.action :as action]
   [pallet.action.directory :as directory]
   [pallet.action.exec-script :as exec-script]
   [pallet.action.file :as file]
   [pallet.action.package :as package]
   [pallet.action.remote-file :as remote-file]
   [pallet.blobstore :as blobstore]
   [pallet.build-actions :as build-actions]
   [pallet.compute :as compute]
   [pallet.core :as core]
   [pallet.crate.automated-admin-user :as automated-admin-user]
   [pallet.crate.cinderella :as cinderella]
   [pallet.crate.java :as java]
   [pallet.crate.jetty :as jetty]
   [pallet.live-test :as live-test]
   [pallet.parameter :as parameter]
   [pallet.parameter-test :as parameter-test]
   [pallet.phase :as phase]
   [pallet.script.lib :as lib]
   [pallet.session :as session]
   [pallet.stevedore :as stevedore]
   [pallet.thread-expr :as thread-expr]))

(defn settings-map
  [session]
  (let [auth (cinderella/compute-service-details (:compute session))]
    {:backend-identity (:identity auth)
     :backend-credential (:credential auth)
     :backend-endpoint (:endpoint auth)}))

(def cinderella-unsupported
  [])

(def-clj-action verify-cinderella
  [session group-name & {:keys [instance-id]}]
  (let [{:keys [home user ec2-port identity credential] :as settings}
        (get-target-settings session :cinderella instance-id ::no-settings)
        node (first (nodes-in-group session group-name))
        endpoint (format "http://%s:%s/" (primary-ip node) ec2-port)
        _ (logging/debugf "Testing with %s %s %s" identity credential endpoint)
        c (compute-service "aws-ec2" identity credential
                           :jclouds.endpoint endpoint)
        images (.. c getContext
                   (unwrap org.jclouds.ec2.EC2ApiMetadata/CONTEXT_TOKEN)
                   getApi getAMIServices (describeImagesInRegion nil nil))]
    (is c "Compute returned")
    (is (seq images) "Compute useable")
    (logging/infof "Compute images %s" (vec images))
    session))

(deftest live-test
  (live-test/test-for
   [image [{:os-family :ubuntu :os-64-bit true}]]
   (live-test/test-nodes
    [compute node-map node-types]
    {:cinderella
     {:image image
      :count 1
      :phases {:bootstrap (phase/phase-fn
                           (automated-admin-user/automated-admin-user))
               :settings (fn [session]
                           (->
                            session
                            (java/java-settings {})
                            (jetty/jetty-settings {})
                            (cinderella/cinderella-settings
                             (settings-map session))))
               :configure (phase/phase-fn
                           (java/install-java)
                           (jetty/install-jetty)
                           (jetty/install-jetty-service)
                           (cinderella/install-cinderella)
                           (cinderella/configure-cinderella)
                           (jetty/init-service
                            ;; :if-config-changed true
                            :action :restart))
               :verify (phase/phase-fn
                        (verify-cinderella :cinderella))}}}
    (core/lift (:cinderella node-types) :phase :verify :compute compute))))
