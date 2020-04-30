(ns puumerkki.main
  (:require
     [puumerkki.codec :as codec]
     [puumerkki.pdf :as pdf]

     [ring.adapter.jetty :as jetty]
     [ring.middleware.params :as params]            ;; query string & body params
     [ring.middleware.multipart-params :as mparams] ;; post body
     [hiccup.core :refer [html]]
     [clojure.data.json :as json])

  (:import [org.apache.pdfbox.pdmodel.interactive.digitalsignature PDSignature SignatureInterface]
           [org.apache.pdfbox.pdmodel PDDocument]
           ;[org.apache.pdfbox.pdmodel.graphics.xobject PDPixelMap PDXObject PDJpeg]
           [org.apache.pdfbox.io RandomAccessFile]
           [org.apache.commons.io IOUtils]
           (org.apache.pdfbox.cos COSName)
           (java.security MessageDigest)
           [java.util Calendar]
           [java.io File FileInputStream FileOutputStream ByteArrayOutputStream]
           (org.apache.commons.codec.digest DigestUtils)))


(pdf/add-signature-space "pdf/testi.pdf" "pdf/testi.pdf-signable" "Anni Allekirjoittaja")  ;; make pdf/testi.pdf-signable
(def test-pdf-data (pdf/read-file "pdf/testi.pdf-signable"))          ;;
(def test-pdf-pkcs (pdf/compute-base64-pkcs test-pdf-data))       ;; to be sent to mpollux from https frontend


(def js-code
(str "var pkcs = '" test-pdf-pkcs "';

var mpolluxInfo = false;
var mpolluxUrl  = 'https://localhost:53952';
var signature   = false;

function renderObject(name,exp) {
   info = name + ':<ul>';
   Object.keys(exp).forEach(function(key) {
      var val = '' + exp[key];
      if (val.length > 100) {
         val = '<font size=-4>' + val + '</font>';
      }
      info += '<li>' + key + ': ' + val;
   });
   info += '</ul>'
   return info;
}

function getVersion() {
   var http = new XMLHttpRequest();
   http.open('GET', mpolluxUrl + '/version');
   http.setRequestHeader('Content-Type', 'application/json; charset=UTF-8');
   http.overrideMimeType('application/json'); // avoid xml parsing error due to missing mime
   document.getElementById('mpollux').innerHTML= 'Ladataan tietoja...';

   http.onreadystatechange = function() {
     if (this.readyState == 4 && this.status == 200) {
        mpolluxInfo = JSON.parse(this.responseText);
        console.log(mpolluxInfo);
        document.getElementById('mpollux').innerHTML = renderObject('versiotiedot', mpolluxInfo);
     } else {
        console.log('status ' + this.readyState);
     }
   }
   http.onloadend = function () {};
   http.send();
}

var test1 = {'selector':{},
             'content':pkcs,
             'contentType':'data',
             'hashAlgorithm':'SHA256',
             'signatureType':'signature',
             'version':'1.1'};

var test1x = {'selector': {
                          'keyusages': ['digitalsignature'],   // signature fails if this is used, even though it seems to work as intended up to selection of certificate
                          'keyalgorithms': ['rsa']
                          },
       'content':pkcs,
       'contentType':'data',
       'hashAlgorithm':'SHA256',
       'signatureType':'signature',
       'version':'1.1'};


function getSignature() {
   if (!mpolluxInfo) {
      alert('hae ensin versiotiedot');
      return;
   }
   var http = new XMLHttpRequest();
   http.open('POST', mpolluxUrl + '/sign', true);
   http.setRequestHeader('Content-Type', 'application/json; charset=UTF-8');
   http.overrideMimeType('application/json'); // mpollux ei lähetä, tulee muuten xml parsintavirhe
   http.onreadystatechange = function() {
     if (this.readyState == 4 && this.status == 200) {
        console.log('About to parse: ' + this.responseText);
        signature = JSON.parse(this.responseText);
        document.getElementById('signature').innerHTML = renderObject('allekirjoitus', signature);

     } else {
        console.log('status ' + this.readyState);
     }
   }
   http.onloadend = function () {};
   console.log('Sending ' + JSON.stringify(test1));
   http.send(JSON.stringify(test1));
}

function sendSignature() {
   if (!signature) {
      alert('tee ensin allekirjoitus');
      return;
   }
   var http = new XMLHttpRequest();
   http.open('POST', '/sign', true);
   http.setRequestHeader('Content-Type', 'application/json; charset=UTF-8');
   http.onreadystatechange = function() {
     if (this.readyState == 4 && this.status == 200) {
        document.getElementById('sent').innerHTML = 'ok';
     } else {
        document.getElementById('sent').innerHTML = 'failed';
     }
   }
   http.onloadend = function () {};
   http.send(JSON.stringify(signature));
}

function verifySignature() {
   var http = new XMLHttpRequest();
   http.open('GET', '/verify', true);
   http.onreadystatechange = function() {
     if (this.readyState == 4 && this.status == 200) {
        document.getElementById('status').innerHTML = this.response;
        console.log(this);
     } else {
        document.getElementById('status').innerHTML = 'error';
     }
   }
   http.onloadend = function () {};
   http.send(JSON.stringify(signature));
}

"))


(defn router [req]
   ; (println "router: " req)
   (cond

      (= "/sign" (:uri req))
         (let [data (json/read-str (:body req) :key-fn keyword)
               pdf-data (pdf/read-file "pdf/testi.pdf-signable")
               pkcs7 (pdf/make-pkcs7 data pdf-data)
             ]
            (pdf/write-file! "pdf/test-allekirjoitettu.pdf"
               (pdf/write-signature! pdf-data pkcs7))
            {:status 200
             :headers {"Content-Type" "text/plain"}
             :body "ok"})

      (= "/verify" (:uri req))
         (let [pdf-data (pdf/read-file "pdf/test-allekirjoitettu.pdf")
               sigp (pdf/cursory-verify-signature pdf-data)
             ]
            (if sigp
               {:status 200
                :headers {"Content-Type" "text/plain"}
                :body 
                   (str "FOO <pre>"
                      (with-out-str 
                         (clojure.pprint/pprint sigp))
                      "</pre>")
               }
               {:status 300
                :headers {"Content-Type" "text/plain"}
                :body "Not good."}))

      :else
         {:status 200
          :body
             (html
                [:html
                   [:head
                      [:title "allekirjoitustesti"]
                      [:script js-code]
                      [:meta {:charset "UTF-8"}]]
                   [:body
                      ;; fiksataan tietty allekirjoitus-hash
                      [:p "Version haku: "
                         [:button {:onClick "getVersion()"}
                         "hae"]]
                      [:p {:id "mpollux"} ""]
                      [:p "Allekirjoituksen testaus: "
                         [:button {:onClick "getSignature()"}
                         "allekirjoita"]]
                      [:p {:id "signature"} ""]
                      [:p "Allekirjoituksen tallennus: "
                         [:button {:onClick "sendSignature()"}
                         "lähetä"]]
                      [:p {:id "sent"} ""]
                      [:p "Allekirjoituksen varmistus: "
                         [:button {:onClick "verifySignature()"}
                         "tarkasta"]]
                      [:p {:id "status"} ""]
                      
                      ]])}))

(defn wrap-content-type [response content-type]
   (if (get-in response [:headers "Content-Type"])
      response
      (assoc-in response [:headers "Content-Type"] content-type)))

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
      ))

(defn go []
   (jetty/run-jetty app {:port 3000 :join false}))

(defn -main [& args]
   (jetty/run-jetty app {:port 3000 :join false}))

