(ns 
    #^{:author "Matt Revelle"
       :doc "OAuth client library for Clojure."} 
  oauth.client
  (:require [oauth.digest :as digest]
            [com.twinql.clojure.http :as http])
  (:use [clojure.contrib.string :as s :only ()]))

(declare rand-str
         base-string
         sign
         url-encode
         oauth-params
         success-content)

(defstruct #^{:doc "OAuth consumer"} consumer
  :key
  :secret
  :request-uri
  :access-uri
  :authorize-uri
  :signature-method)

(defn make-consumer
  "Make a consumer struct map."
  [key secret request-uri access-uri authorize-uri signature-method]
  (struct consumer 
          key
          secret
          request-uri 
          access-uri 
          authorize-uri 
          signature-method))

;;; Parse form-encoded bodies from OAuth responses.
(defmethod http/entity-as :urldecoded [entity as]
  (into {}
        (map (fn [kv]
               (let [[k v] (s/split #"=" kv)]
                 [(keyword k) v]))
             (s/split #"&" (http/entity-as entity :string)))))

(defn request-token
  "Fetch request token for the consumer."
  [consumer]
  (let [unsigned-params (oauth-params consumer)
        params (assoc unsigned-params :oauth_signature (sign consumer 
                                                             (base-string "POST" 
                                                                          (:request-uri consumer)
                                                                          unsigned-params)))]
    (success-content
      (http/post (:request-uri consumer)
                 :query params
                 :parameters (http/map->params {:use-expect-continue false})
                 :as :urldecoded))))

(defn user-approval-uri
  "Builds the URI to the Service Provider where the User will be prompted
to approve the Consumer's access to their account."
  ([consumer token]
     (.toString (http/resolve-uri (:authorize-uri consumer) 
                                  {:oauth_token token})))
  ([consumer token callback-uri]
     (.toString (http/resolve-uri (:authorize-uri consumer) 
                                  {:oauth_token token
                                   :oauth_callback callback-uri}))))

(defn access-token 
  "Exchange a request token for an access token.
  When provided with two arguments, this function operates as per OAuth 1.0.
  With three arguments, a verifier is used:

      http://wiki.oauth.net/Signed-Callback-URLs

  This allows Twitter's PIN pass-back:

      http://apiwiki.twitter.com/Authentication"
  ([consumer request-token]
     (access-token consumer request-token nil))
  ([consumer request-token verifier]
     (let [unsigned-params (oauth-params consumer request-token verifier)
           params (assoc unsigned-params
                    :oauth_signature (sign consumer
                                           (base-string "POST"
                                                        (:access-uri consumer)
                                                        unsigned-params)))]
       (success-content
        (http/post (:access-uri consumer)
                   :query params
                   :parameters (http/map->params {:use-expect-continue false})
                   :as :urldecoded)))))

(defn credentials
  "Return authorization credentials needed for access to protected resources.  
The key-value pairs returned as a map will need to be added to the 
Authorization HTTP header or added as query parameters to the request."
  [consumer token token-secret request-method request-uri & [request-params]]
  (let [unsigned-oauth-params (oauth-params consumer token)
        unsigned-params (merge request-params 
                               unsigned-oauth-params)]
    (assoc unsigned-oauth-params :oauth_signature (sign consumer
                                                        (base-string (-> request-method
                                                                         s/as-str
                                                                         s/upper-case)
                                                                     request-uri
                                                                     unsigned-params)
                                                        token-secret))))

(defn authorization-header
  "OAuth credentials formatted for the Authorization HTTP header."
  [realm credentials]
  (str "OAuth " (s/join "," (map (fn [[k v]] 
                                     (str (s/as-str k) "=\"" v "\""))
                                   (assoc credentials :realm realm)))))

(defn rand-str
  "Random string for OAuth requests."
  [length]
  (let [valid-chars (map char (concat (range 48 58)
                                      (range 97 123)))
        rand-char #(nth valid-chars (rand (count valid-chars)))]
    (apply str (take length (repeatedly rand-char)))))

(defn base-string
  [method base-url params]
  (s/join "&" [method
                 (url-encode base-url) 
                 (url-encode (s/join "&" (map (fn [[k v]]
                                                  (str (name k) "=" v))
                                                (sort params))))]))

(defmulti sign 
  "Sign a base string for authentication."
  (fn [c & r] (:signature-method c)))

(defmethod sign :hmac-sha1
  [c base-string & [token-secret]]
  (let [key (str (:secret c) "&" (or token-secret ""))]
    (digest/hmac key base-string)))

(defn url-encode
  "The java.net.URLEncoder class encodes for application/x-www-form-urlencoded, but OAuth
requires RFC 3986 encoding."
  [s]
  (-> (java.net.URLEncoder/encode s "UTF-8")
    (.replace "+" "%20")
    (.replace "*" "%2A")
    (.replace "%7E" "~")))

(defn oauth-params
  "Build a map of parameters needed for OAuth requests."
  ([consumer]
     {:oauth_consumer_key (:key consumer)
      :oauth_signature_method "HMAC-SHA1"
      :oauth_timestamp (System/currentTimeMillis)
      :oauth_nonce (rand-str 30)
      :oauth_version "1.0"})
  ([consumer token]
     (assoc (oauth-params consumer) 
       :oauth_token token))
  ([consumer token verifier]
     (if verifier
       (assoc (oauth-params consumer token) :oauth_verifier verifier)
       (oauth-params consumer token))))

(defn check-success-response [m]
  (let [code (:code m)]
    (if (or (< code 200)
            (>= code 300))
      (throw (new Exception (str "Got non-success response " code ".")))
      m)))

(defn success-content [m]
  (:content
    (check-success-response m)))
