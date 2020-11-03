(ns puumerkki.main
   (:require
      [puumerkki.codec :as codec]
      [puumerkki.pdf :as pdf]
      [puumerkki.crypt :as crypt]
      [pandect.algo.sha256 :refer :all]
      [ring.adapter.jetty :as jetty]
      [ring.middleware.params :as params]            ;; query string & body params
      [ring.middleware.multipart-params :as mparams] ;; post body
      [hiccup.core :refer [html]]
      [clj-http.client :as http]
      [clojure.data.json :as json])

   (:import
      [org.apache.pdfbox.pdmodel.interactive.digitalsignature PDSignature SignatureInterface]
      [org.apache.pdfbox.pdmodel PDDocument]
      ;[org.apache.pdfbox.pdmodel.graphics.xobject PDPixelMap PDXObject PDJpeg]
      [org.apache.pdfbox.io RandomAccessFile]
      [org.apache.commons.io IOUtils]
      (org.apache.pdfbox.cos COSName)
      (java.security MessageDigest)
      [java.util Calendar]
      [java.io File FileInputStream FileOutputStream ByteArrayOutputStream]
      (org.apache.commons.codec.digest DigestUtils))

   (:gen-class))


;;
;; A minimal test server
;;

(defonce auth-secret (atom nil))

(defonce trust-roots (atom nil))

(defn load-trust-roots! [pem-path]
   (reset! trust-roots
      (crypt/load-trust-roots pem-path)))

(def origin "https://localhost")

(pdf/add-watermarked-signature-space "pdf/testi.pdf" "pdf/testi.pdf-signable" "Stephen Signer" "pdf/stamp.jpeg" 100 300)

(def test-pdf-data (pdf/read-file "pdf/testi.pdf-signable"))      ;;

(defn authentication-hash [data]
   (sha256-bytes data))

(def auth-payload "8fe85f31aa6fa5ff4e5cb7d03497e9a78c0f8492ec2da21279adedbc312976cf")

(defonce a-js-code (atom nil))
(defn make-js-code []

(str "
var mpolluxInfo  = false;
var mpolluxUrl   = 'https://localhost:53952';
var signature    = false;
var authresponse = false;

function toSmallString(val) {
   let s = val + '';
   if (s.length > 100) {
      s = '<font size=-4>' + val + '</font>';
   }
   return s;
}

function showMe(e, text) {
   e.innerHTML = text;
   e.style.display = 'inline-block';
}

function clearMe(e) {
   e.innerHTML = '';
   e.style.display = 'none';
}

function showId(id, text) {
   let elem = document.getElementById(id);
   if (elem) {
      showMe(elem, text);
   } else {
      console.log('cannot find object with id ' + id);
   }
}

function showIdSpin(id, text) {
   showId(id, text + ' <div class=spinner></div>');
}

function renderObject(name,exp) {
   let info = name + ':<ul>';
   Object.keys(exp).forEach(function(key) {
      let val = exp[key];
      let rep;
      if (Array.isArray(val)) {
         rep = '<ol>';
         val.forEach(function(val) {
            rep += '<li>' + toSmallString(val);
         });
         rep += '</ol>';
      } else {
         rep = toSmallString(val);
      }
      info += '<li>' + key + ': ' + rep;
   });
   info += '</ul>'
   return info;
}


// reloads each time without caching, so that we'll see whether digisign is
// alive at frontend before attempting to use it
function getVersion(target, cont) {
   let http = new XMLHttpRequest();
   http.open('GET', mpolluxUrl + '/version');
   http.setRequestHeader('Content-Type', 'application/json; charset=UTF-8');
   http.overrideMimeType('application/json'); // avoid xml parsing error due to missing mime type

   http.onreadystatechange = function() {
     if (this.readyState != 4) return;
     if (this.status == 200) {
        mpolluxInfo = JSON.parse(this.responseText);
        if (cont) {
           cont(target, mpolluxInfo);
        } else {
           showId(target, renderObject('versiotiedot', mpolluxInfo));
        }
     } else {
        showId(target, 'Digisign ei käytettävissä');
     }
   }
   http.timeout = 2000;
   showId(target, 'yhdistetään digisigniin');
   http.send();
}

function loadCAs() {
   var http = new XMLHttpRequest();
   http.open('GET', 'load-cas');
   http.onreadystatechange = function() {
     if (this.readyState != 4) return;
     if (this.status == 200) {
        showId('cas', this.responseText);
     } else {
        showId('cas', 'error');
     }
   }
   showIdSpin('cas');
   http.send();
}

function makeSignature(target, request) {
   var http = new XMLHttpRequest();
   http.open('POST', mpolluxUrl + '/sign', true);
   http.setRequestHeader('Content-Type', 'application/json; charset=UTF-8');
   http.overrideMimeType('application/json');
   http.onreadystatechange = function() {
     if (this.readyState != 4) return;
     if (this.status == 200) {
        signature = JSON.parse(this.responseText);
        sendSignature(target, signature); // + request w/ hmac later to avoid backend state
     } else {
        showId(target, 'failed');
     }
   }
   showIdSpin(target, 'allekirjoitetaan kortilla');
   http.send(JSON.stringify(request));
}

function sendSignature(target, signature) {
   var http = new XMLHttpRequest();
   http.open('POST', 'sign', true);
   http.setRequestHeader('Content-Type', 'application/json; charset=UTF-8');
   http.onreadystatechange = function() {
     if (this.readyState != 4) return;
     if (this.status == 200) {
        verifySignature(target);
     } else {
        showId(target, 'failed');
     }
   }
   showIdSpin(target, 'lähetetään');
   http.send(JSON.stringify(signature));
}

function verifySignature(target) {
   var http = new XMLHttpRequest();
   http.open('GET', 'verify', true);
   http.onreadystatechange = function() {
     if (this.readyState != 4) return;
     if (this.status == 200) {
        showId(target, 'OK'); // this.response
     } else {
        showId(target, 'allekirjoitusta ei hyväksytä');
     }
   }
   showIdSpin(target, 'varmistetaan allekirjoitus');
   http.send(JSON.stringify(signature));
}

function sendAuth(target, response, challenge) {
   var http = new XMLHttpRequest();
   http.open('POST', 'authenticate', true);
   http.setRequestHeader('Content-Type', 'application/json; charset=UTF-8');
   http.onreadystatechange = function() {
     if (this.readyState != 4) return;
     if (this.status == 200) {
        let signerInfo = JSON.parse(this.responseText);
        showId(target, renderObject('Käyttäjä tunnistettu',  signerInfo));
     } else {
        showId(target, 'failed (backend)');
     }
   }
   response['signedData'] = challenge; // return to backend for verification
   showId(target, 'lähetetään tiedot');
   http.send(JSON.stringify(response));
}

function authenticate(target, challenge) {
   var http = new XMLHttpRequest();
   http.open('POST', mpolluxUrl + '/sign', true);
   http.setRequestHeader('Content-Type', 'application/json; charset=UTF-8');
   http.onreadystatechange = function() {
     if (this.readyState != 4) return;
     if (this.status == 200) {
        authresponse = JSON.parse(this.responseText);
        if (authresponse.status == 'ok') {
           sendAuth(target, authresponse, challenge);
        } else {
           showId(target, 'failed (card)');
        }
     } else {
        showId(target, 'failed (digisign)');
     }
   }
   showId(target, 'toimikortti');
   http.send(JSON.stringify(challenge));
}

function getAuthChallenge(target, version) {
   let args = {};
   let http = new XMLHttpRequest();
   http.open('POST', 'auth-challenge', true);
   http.onreadystatechange = function() {
     if (this.readyState != 4) return;
     if (this.status == 200) {
        authenticate(target, JSON.parse(this.responseText));
     } else {
        showId(target, 'failed (backend challenge)');
     }
   }
   showId(target, 'starting authentication');
   args.host = window.location.hostname;
   args.version = version;
   args.url = mpolluxUrl;
   args.type = 'digisign'; // for future extension
   http.send(JSON.stringify(args));
}

function startAuthentication() {
   if (location.protocol !== \"https:\") {
      alert('must authenticate over https');
      return;
   }
   getVersion('authentication', getAuthChallenge);
}

function getPdfSignRequest(target, version) {
   let args = {};
   let http = new XMLHttpRequest();
   http.open('POST', 'pre-sign', true);
   http.onreadystatechange = function() {
     if (this.readyState != 4) return;
     if (this.status == 200) {
        makeSignature(target, JSON.parse(this.responseText));
     } else {
        showId(target, 'pre-sign');
     }
   }
   args.host = window.location.hostname;
   args.version = version;
   args.url = mpolluxUrl;
   args.type = 'digisign'; // for future extension
   showIdSpin(target, 'haetaan tiedot allekirjoitusta varten');
   http.send(JSON.stringify(args));
}

function startSigning(target) {
   if (location.protocol !== \"https:\") {
      alert('can only sign via https');
      return;
   }
   showIdSpin(target, 'aloitetaan');
   getVersion(target, getPdfSignRequest);
}

"))

(def style
"
body {
   padding: 20px;
}
h1 {
   padding-above: 20px;
}
.result {
   margin-left: 30px;
   padding: 20px;
   border: solid black 1px;
   display: none;
}
.spinner {
  width: 14px;
  height: 14px;
  border: solid black 1px;
  animation-name: spin;
  display: flex;
  animation-duration: 2000ms;
  animation-iteration-count: infinite;
  animation-timing-function: linear;
}
@keyframes spin {
  from {transform:rotate(0deg);}
  to {transform:rotate(360deg);}
}
")

(defn cert-givenname [cert]
   (let [node
         (codec/asn1-find cert
            [:sequence [:identifier 2 5 4 4] :printable-string])]
      (if node
         (-> node (nth 2) (nth 1))
         nil)))

(defn cert-surname [cert]
   (let [node
         (codec/asn1-find cert
            [:sequence [:identifier 2 5 4 42] :printable-string])]
      (if node
         (-> node (nth 2) (nth 1))
         nil)))

(defn log-halko [& what]
   (future
      (http/post "http://localhost:3001/log"
         {:form-params {:service "puumerkki" :msg (apply str what)}})))

(defn pdf-sign-request [version]
   (let [pkcs (pdf/compute-base64-pkcs test-pdf-data)]
      (str "{\"selector\":{\"keyusages\":[\"nonrepudiation\"]},
             \"content\":\"" (pdf/compute-base64-pkcs test-pdf-data) "\",
             \"contentType\":\"data\",
             \"hashAlgorithm\":\"SHA256\",
             \"signatureType\":\"signature\",
             \"version\":\"1.1\"}")))

(defn fold [o s l]
   (if (empty? l)
      s
      (o (first l)
         (fold o s (rest l)))))

(defn remove [filter seq]
   (fold
      (fn [elem tail]
         (if (filter elem)
            tail
            (cons elem tail)))
      nil seq))

(defn router [req]
   (log-halko (:uri req))

   (cond

      ;; PDF part

      (= "/sign" (:uri req))
         (let [data (json/read-str (:body req) :key-fn keyword)]
            (if-let
               [errs
                  (remove
                     (partial = :signature-not-valid) ;; test cards currently not valid
                     (crypt/validation-errors
                        @trust-roots
                        (:signature data)
                        (byte-array
                           ; (codec/base64-decode-octets test-pdf-pkcs)
                           (list)
                           )
                        (:chain data)))]
               (do
                  (log-halko "PDF signature failure: " errs)
                  {:status 400
                   :headers {"Content-Type" "text/plain"}
                   :body (str "Invalid signature: " errs)})
               (let [pdf-data (pdf/read-file "pdf/testi.pdf-signable")
                     pkcs7 (pdf/make-pkcs7 data pdf-data)]
                  ;; crossvalidate pdf with signature later
                  (log-halko "PDF signed")
                  (pdf/write-file! "pdf/test-allekirjoitettu.pdf"
                     (pdf/write-signature! pdf-data pkcs7))
                  {:status 200
                   :headers {"Content-Type" "text/plain"}
                   :body "OK"})))

      (= "/verify" (:uri req))
         (let [pdf-data (pdf/read-file "pdf/test-allekirjoitettu.pdf")
               sigp (pdf/cursory-verify-signature pdf-data)]
            (log-halko (if sigp "/verify OK" "/verify FAILED"))
            (if sigp
               {:status 200
                :headers {"Content-Type" "text/plain"}
                :body
                   (str "OK: <div>"
                      (with-out-str
                         ; (clojure.pprint/pprint sigp)
                         (println sigp)
                         )
                      "</div>")
               }
               {:status 300
                :headers {"Content-Type" "text/plain"}
                :body "Virhe."}))

      (= "/pre-sign" (:uri req))
         (let [req (json/read-str (:body req) :key-fn keyword)]
            (let [request (pdf-sign-request (get req :version {}))]
               {:status 200
                :headers {"Content-Type" "application/json"}
                :body request}))

      ;; Authentication

      (= "/authenticate" (:uri req))
         (let [data (json/read-str (:body req) :key-fn keyword)
               challenge (-> data (get :signedData) (get :content)
                           codec/base64-decode)]

            (let [signer (and data (crypt/verify-authentication-challenge @trust-roots @auth-secret data challenge auth-payload))]
               (if signer
                  (do
                     (log-halko (if signer "/authenticate OK" "/authenticate FAILED"))
                     {:status 200
                      :headers {"Content-Type" "text/plain"}
                      :body (json/write-str signer)})
                  (do
                     (log-halko "/authenticate failed")
                     {:status 300
                      :headers {"Content-Type" "text/plain"}
                      :body "No"}))))

      (= "/auth-challenge" (:uri req))
         (let [req (json/read-str (:body req) :key-fn keyword)]
            (log-halko "fetching authentication challenge: " req)
            (let [request (crypt/digisign-authentication-request
                               @auth-secret
                               (get req :host "localhost")
                               (get req :version {})
                               auth-payload)]
               (log-halko request)
               {:status 200
                :headers {"Content-Type" "application/json"}
                :body request}))


      ;; Meta

      (= "/load-cas" (:uri req))
         (do
            (log-halko "loading trust roots")
            (load-trust-roots! "trust-roots.pem")
            {:status 200
             :headers {"Content-Type" "text/html"}
             :body
                (str "<ul>"
                   (reduce
                      (fn [tail cert]
                         (str "<li>" (crypt/cert-name cert) tail))
                      "</ul>"
                      @trust-roots))})

      ;; Test page

      :else
         {:status 200
          :body
             (html
                [:html
                   [:head
                      [:title "Puumerkki"]
                      [:meta {:charset "UTF-8"}]]
                      [:script @a-js-code]
                      [:style  style]
                   [:body

                      [:h1 "Tunnistautuminen"]

                      [:p "Tunnistautuminen: "
                         [:button {:onClick "startAuthentication()"}
                         "tunnistaudu"]]
                      [:div {:id "authentication" :class "result" :onClick "clearMe(this)"} ""]

                      [:h1 "PDF Allekirjoitus"]

                      [:p "Allekirjoita: "
                         [:button {:onClick "startSigning('signature')"}
                         "allekirjoita"]]
                      [:div {:id "signature" :class "result" :onClick "clearMe(this)"} ""]

                      [:h1 "Työkalut"]

                      [:p "Digisign version haku: "
                         [:button {:onClick "getVersion('mpollux', false)"}
                         "hae"]]
                      [:div {:id "mpollux" :class "result" :onClick "clearMe(this)"} ""]

                      [:p "Varmenteet: "
                         [:button {:onClick "loadCAs()"}
                         "lataa"]]
                      [:div {:id "cas" :class "result" :onClick "clearMe(this)"} ""]

                      [:p "Revokaatio: "
                         [:button {:onClick "loadRevocation()"}
                         "päivitä"]]
                      [:div {:id "cas" :class "result" :onClick "clearMe(this)"} ""]



                      ]])}))

(defn wrap-default-content-type [handler]
   (fn [req]
      (let [response (handler req)]
         (if (get-in response [:headers "Content-Type"])
            response
            (assoc-in response [:headers "Content-Type"] "text/html")))))

(defn wrap-body-string [handler]
  (fn [request]
      (let [body-str (ring.util.request/body-string request)]
         (handler (assoc request :body body-str)))))

(def app
   (->
      router
      (mparams/wrap-multipart-params)
      (params/wrap-params)
      (wrap-body-string)
      (wrap-default-content-type)
      ))

(def default-opts
   {:port 3000
    :join? false
    :host "localhost"                 ;; where to accept connections from
    :trust-roots "trust-roots.pem"    ;; accepted roots for signatures & authentication
    :hmac-secret "randomized"         ;; authentication challenge signing secret
    })


(defn start [opts]
   ;; possibly move out of crypt
   (load-trust-roots!
      (get opts :trust-roots))
   (reset! auth-secret
      (get opts :hmac-secret))
   (reset! a-js-code
      (make-js-code))
   (log-halko "starting up")
   (if @trust-roots
      (jetty/run-jetty app
         (select-keys opts [:port :join? :host]))
      (println "Cannot start demo app")))

(defn go []
   (start default-opts))

(defn handle-args [opts args]
   (cond
      (empty? args)
         (start opts)
      (contains? #{"-r" "--allow-remote-access"} (first args))
         ;; allow access from hosts other than localhost
         (handle-args (dissoc opts :host) (rest args))
      (contains? #{"-h" "--help"} (first args))
         (do
            (println "Usage: java -jar puumerkki.jar [-h] [-t|--trust-roots <pemfile>]")
            0)
      (and (contains? #{"-t" "--trust-roost"} (first args)) (not (empty? (rest args))))
         (handle-args (assoc opts :trust-roots (nth args 1))
                      (rest (rest args)))
      (and (contains? #{"-s" "--secret"} (first args)) (not (empty? (rest args))))
         (handle-args (assoc opts :hmac-secret (nth args 1))
                      (rest (rest args)))
      :else
         (do
            (println "Invalid arguments: " args ". Use -h for help.")
            nil)))

(defn -main [& args]
   (try
      (handle-args default-opts args)
      (catch Exception e
         (println "ERROR: " e)
         1)))

