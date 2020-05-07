(ns frontend.handler
  (:refer-clojure :exclude [clone load-file])
  (:require [frontend.git :as git]
            [frontend.fs :as fs]
            [frontend.state :as state]
            [frontend.db :as db]
            [frontend.storage :as storage]
            [frontend.search :as search]
            [frontend.util :as util]
            [frontend.config :as config]
            [frontend.diff :as diff]
            [clojure.walk :as walk]
            [clojure.string :as string]
            [promesa.core :as p]
            [cljs-bean.core :as bean]
            [reitit.frontend.easy :as rfe]
            [reitit.frontend.history :as rfh]
            [goog.crypt.base64 :as b64]
            [goog.object :as gobj]
            [goog.dom :as gdom]
            [rum.core :as rum]
            [datascript.core :as d]
            [dommy.core :as dom]
            [frontend.utf8 :as utf8]
            [frontend.image :as image]
            [clojure.set :as set]
            [cljs-bean.core :as bean]
            [frontend.format :as format]
            [frontend.format.protocol :as protocol]
            [frontend.format.block :as block])
  (:import [goog.events EventHandler]
           [goog.format EmailAddress]))

;; TODO: replace all util/p-handle with p/let
;; TODO: separate git status for push-failed, pull-failed, etc
(defn set-state-kv!
  [key value]
  (swap! state/state assoc key value))

(defn get-github-token
  []
  (get-in @state/state [:me :access-token]))

(defn load-file
  [repo-url path]
  (->
   (p/let [content (fs/read-file (git/get-repo-dir repo-url) path)]
     content)
   (p/catch
       (fn [e]
         (prn "load file failed, " e)))))

(defn redirect!
  "If `push` is truthy, previous page will be left in history."
  [{:keys [to path-params query-params push]
    :or {push true}}]
  (if push
    (rfe/push-state to path-params query-params)
    (rfe/replace-state to path-params query-params)))

(defn redirect-with-fragment!
  [path]
  (.pushState js/window.history nil "" path)
  (rfh/-on-navigate @rfe/history path))

(defn- hidden?
  [path patterns]
  (some (fn [pattern]
          (or
           (= path pattern)
           (and (string/starts-with? pattern "/")
                (= (str "/" (first (string/split path #"/")))
                   pattern)))) patterns))

(defn- keep-formats
  [files formats]
  (filter
   (fn [file]
     (let [format (format/get-format file)]
       (contains? formats format)))
   files))

(defn- only-text-formats
  [files]
  (keep-formats files (config/text-formats)))

(defn- only-html-render-formats
  [files]
  (keep-formats files config/html-render-formats))

;; TODO: no atom version
(defn load-files
  [repo-url]
  (state/set-cloning? false)
  (set-state-kv! :repo/loading-files? true)
  (p/let [files (bean/->clj (git/list-files repo-url))
          config-content (load-file repo-url config/config-file)]
    (if config-content
      (let [config (db/reset-config! repo-url config-content)]
        (if-let [patterns (seq (:hidden config))]
          (remove (fn [path] (hidden? path patterns)) files)
          files))
      files)))

(defn- set-latest-commit!
  [repo-url hash]
  (db/set-key-value repo-url :git/latest-commit hash))

(defn- set-git-status!
  [repo-url value]
  (db/set-key-value repo-url :git/status value))

(defn- set-git-error!
  [repo-url value]
  (db/set-key-value repo-url :git/error (if value (str value))))

(defn git-add
  [repo-url file]
  (p/let [_ (git/add repo-url file)]
    (set-git-status! repo-url :should-push)))

;; journals

;; org-journal format, something like `* Tuesday, 06/04/13`
(defn default-month-journal-content
  []
  (let [{:keys [year month day]} (util/get-date)
        last-day (util/get-month-last-day)
        month-pad (if (< month 10) (str "0" month) month)]
    (->> (map
           (fn [day]
             (let [day-pad (if (< day 10) (str "0" day) day)
                   weekday (util/get-weekday (js/Date. year (dec month) day))]
               (str "* " weekday ", " month-pad "/" day-pad "/" year "\n")))
           (range 1 (inc last-day)))
         (apply str))))

(defn create-month-journal-if-not-exists
  [repo-url]
  (let [repo-dir (git/get-repo-dir repo-url)
        path (util/current-journal-path)
        file-path (str "/" path)
        default-content (default-month-journal-content)]
    (p/let [_ (-> (fs/mkdir (str repo-dir "/journals"))
                  (p/catch (fn [_e])))
            file-exists? (fs/create-if-not-exists repo-dir file-path default-content)]
      (when-not file-exists?
        (db/reset-file! repo-url path default-content)
        (git-add repo-url path)))))

(defn create-config-file-if-not-exists
  [repo-url]
  (let [repo-dir (git/get-repo-dir repo-url)
        path config/config-file
        file-path (str "/" path)
        default-content "{}"]
    (p/let [file-exists? (fs/create-if-not-exists repo-dir file-path default-content)]
      (when-not file-exists?
        (db/reset-file! repo-url path default-content)
        (git-add repo-url path)))))

(defn load-files-contents!
  [repo-url files ok-handler]
  (let [files (only-text-formats files)]
    (p/let [contents (p/all (doall
                             (for [file files]
                               (load-file repo-url file))))]
      (ok-handler
       (zipmap files contents)))))

(defn load-repo-to-db!
  [repo-url diffs first-clone?]
  (set-state-kv! :repo/loading-files? false)
  (set-state-kv! :repo/importing-to-db? true)
  (let [load-contents (fn [files delete-files delete-headings]
                        (load-files-contents!
                         repo-url
                         files
                         (fn [contents]
                           (let [headings-pages (db/extract-all-headings-pages contents)]
                             (db/reset-contents-and-headings! repo-url contents headings-pages delete-files delete-headings)
                             (set-state-kv! :repo/importing-to-db? false)))))]

    (if first-clone?
      (p/let [files (load-files repo-url)]
        (load-contents files nil nil))
      (when (seq diffs)
        (let [filter-diffs (fn [type] (->> (filter (fn [f] (= type (:type f))) diffs)
                                           (map :path)))
              remove-files (filter-diffs "remove")
              modify-files (filter-diffs "modify")
              add-files (filter-diffs "add")
              delete-files (if (seq remove-files)
                             (db/delete-files remove-files))
              delete-headings (db/delete-headings repo-url (concat remove-files modify-files))
              add-or-modify-files (util/remove-nils (concat add-files modify-files))]
          (load-contents add-or-modify-files delete-files delete-headings))))))

(defn journal-file-changed?
  [repo-url diffs]
  (contains? (set (map :path diffs))
             (db/get-current-journal-path)))

(defn load-db-and-journals!
  [repo-url diffs first-clone?]
  (when (or diffs first-clone?)
    (p/let [_ (load-repo-to-db! repo-url diffs first-clone?)]
      (create-month-journal-if-not-exists repo-url)
      (create-config-file-if-not-exists repo-url))))

(defn show-notification!
  [content status]
  (swap! state/state assoc
         :notification/show? true
         :notification/content content
         :notification/status status)
  (js/setTimeout #(swap! state/state assoc
                         :notification/show? false
                         :notification/content nil
                         :notification/status nil)
                 5000))

(defn clear-storage
  []
  (p/let [_idb-clear (js/window.pfs._idb.wipe)]
    (js/localStorage.clear)
    (set! (.-href js/window.location) "/")))

(defn pull
  [repo-url token]
  (let [status (db/get-key-value repo-url :git/status)]
    (when (and
           (not (state/get-edit-input-id))
           ;; (or (nil? status)
           ;;     (= status :pulling))
           )
      (set-git-status! repo-url :pulling)
      (let [latest-commit (db/get-key-value repo-url :git/latest-commit)]
        (p/let [result (git/fetch repo-url token)
                {:keys [fetchHead]} (bean/->clj result)
                _ (set-latest-commit! repo-url fetchHead)]
          (-> (git/merge repo-url)
              (p/then (fn [result]
                        (-> (git/checkout repo-url)
                            (p/then (fn [result]
                                      (set-git-status! repo-url nil)
                                      (when (and latest-commit fetchHead
                                                 (not= latest-commit fetchHead))
                                        (p/let [diffs (git/get-diffs repo-url latest-commit fetchHead)]
                                          (load-db-and-journals! repo-url diffs false)))))
                            (p/catch (fn [error]
                                       (set-git-status! repo-url :checkout-failed)
                                       (set-git-error! repo-url error))))))
              (p/catch (fn [error]
                         (set-git-status! repo-url :merge-failed)
                         (set-git-error! repo-url error)
                         (show-notification!
                          [:p.content
                           "Failed to merge, please "
                           [:span.text-gray-700.font-bold
                            "resolve any diffs first."]]
                          :error)
                         (redirect! {:to :diff})
                         ))))))))

(defn pull-current-repo
  []
  (when-let [repo (state/get-current-repo)]
    (when-let [token (get-github-token)]
      (pull repo token))))

(defn periodically-pull
  [repo-url pull-now?]
  (when-let [token (get-github-token)]
    (when pull-now? (pull repo-url token))
    (js/setInterval #(pull repo-url token)
                    (* (config/git-pull-secs) 1000))))

(defn get-latest-commit
  ([repo-url handler]
   (get-latest-commit repo-url handler 1))
  ([repo-url handler length]
   (-> (p/let [commits (git/log repo-url
                                (get-github-token)
                                length)]
         (handler (if (= length 1)
                    (first commits)
                    commits)))
       (p/catch (fn [error]
                  (prn "get latest commit failed: " error))))))

(defn set-latest-commit-if-exists! [repo-url]
  (get-latest-commit
   repo-url
   (fn [commit]
     (when-let [hash (gobj/get commit "oid")]
       (set-latest-commit! repo-url hash)))))

;; TODO: update latest commit
(defn push
  [repo-url]
  ;; TODO: find un-committed changes, and create a commit
  (when (and
         (not (state/get-edit-input-id))
         (= :should-push (db/get-key-value repo-url :git/status)))
    ;; auto commit if there are any un-committed changes
    (p/let [changed-files (git/get-status-matrix repo-url)]
      (when (seq (flatten (vals changed-files)))
        (p/let [_commit-result (git/commit repo-url "Logseq auto save")]
          (set-git-status! repo-url :pushing)
          (let [token (get-github-token)]
            (util/p-handle
             (git/push repo-url token)
             (fn []
               (prn "Push successfully!")
               (set-git-status! repo-url nil)
               (set-git-error! repo-url nil)
               (set-latest-commit-if-exists! repo-url))
             (fn [error]
               (prn "Failed to push, error: " error)
               (set-git-status! repo-url :push-failed)
               (set-git-error! repo-url error)
               (show-notification!
                [:p.content
                 "Failed to push, please "
                 [:span.text-gray-700.font-bold
                  "resolve any diffs first."]]
                :error)
               (p/let [result (git/fetch repo-url (get-github-token))
                       {:keys [fetchHead]} (bean/->clj result)
                       _ (set-latest-commit! repo-url fetchHead)]
                 (redirect! {:to :diff}))))))))))

(defn commit-and-force-push!
  [commit-message pushing?]
  (let [repo (frontend.state/get-current-repo)]
    (p/let [changes (git/get-status-matrix repo)]
      (let [changes (seq (flatten (concat (vals changes))))]
        (p/let [commit-oid (if changes (git/commit repo commit-message))
                _ (if changes (git/write-ref! repo commit-oid))
                _ (git/push repo
                            (get-github-token)
                            true)]
          (reset! pushing? false)
          (redirect! {:to :home}))))))

(defn db-listen-to-tx!
  [repo db-conn]
  (d/listen! db-conn :persistence
             (fn [tx-report]
               (when-let [db (:db-after tx-report)]
                 (js/setTimeout (fn []
                                  (db/persist repo db)) 0)))))

(defn clone
  [repo-url]
  (let [token (get-github-token)]
    (util/p-handle
     (do
       (state/set-cloning? true)
       (git/clone repo-url token))
     (fn []
       (state/set-current-repo! repo-url)
       (db/start-db-conn! (:me @state/state)
                          repo-url
                          db-listen-to-tx!)
       (db/mark-repo-as-cloned repo-url)
       (set-latest-commit-if-exists! repo-url)
       (util/post (str config/api "repos")
                  {:url repo-url}
                  (fn [result]
                    (swap! state/state
                           update-in [:me :repos]
                           (fn [repos]
                             (util/distinct-by :url (conj repos result)))))
                  (fn [error]
                    (prn "Something wrong!"))))
     (fn [e]
       (state/set-cloning? false)
       (set-git-status! repo-url :clone-failed)
       (set-git-error! repo-url e)
       (prn "Clone failed, reason: " e)))))

(defn new-notification
  [text]
  (js/Notification. "Logseq" #js {:body text
                                  ;; :icon logo
                                  }))

(defn request-notifications
  []
  (util/p-handle (.requestPermission js/Notification)
                 (fn [result]
                   (storage/set :notification-permission-asked? true)

                   (when (= "granted" result)
                     (storage/set :notification-permission? true)))))

(defn request-notifications-if-not-asked
  []
  (when-not (storage/get :notification-permission-asked?)
    (request-notifications)))

;; notify deadline or scheduled tasks
(defn run-notify-worker!
  []
  (when (storage/get :notification-permission?)
    (let [notify-fn (fn []
                      (let [tasks (:tasks @state/state)
                            tasks (flatten (vals tasks))]
                        (doseq [{:keys [marker title] :as task} tasks]
                          (when-not (contains? #{"DONE" "CANCElED" "CANCELLED"} marker)
                            (doseq [[type {:keys [date time] :as timestamp}] (:timestamps task)]
                              (let [{:keys [year month day]} date
                                    {:keys [hour min]
                                     :or {hour 9
                                          min 0}} time
                                    now (util/get-local-date)]
                                (when (and (contains? #{"Scheduled" "Deadline"} type)
                                           (= (assoc date :hour hour :minute min) now))
                                  (let [notification-text (str type ": " (second (first title)))]
                                    (new-notification notification-text)))))))))]
      (notify-fn)
      (js/setInterval notify-fn (* 1000 60)))))

(defn file-changed?
  [content]
  (not= (string/trim content)
        (string/trim (state/get-edit-content))))

(defn alter-file
  [repo-url path content {:keys [reset?]
                          :or {reset? true}}]
  (when reset?
    (db/reset-file! repo-url path content))
  (util/p-handle
   (fs/write-file (git/get-repo-dir repo-url) path content)
   (fn [_]
     (git-add repo-url path))))

;; TODO: utf8 encode performance
(defn check
  [heading]
  (let [repo-url (state/get-current-repo)
        {:heading/keys [file marker meta uuid]} heading
        pos (:pos meta)
        file (db/entity (:db/id file))
        file (:file/path file)]
    (when-let [content (db/get-file-content repo-url file)]
      (let [encoded-content (utf8/encode content)
            content' (str (utf8/substring encoded-content 0 pos)
                          (-> (utf8/substring encoded-content pos)
                              (string/replace-first marker "DONE")))]
        (alter-file repo-url file content' nil)))))

(defn uncheck
  [heading]
  (let [repo-url (state/get-current-repo)
        {:heading/keys [file marker meta]} heading
        pos (:pos meta)
        file (db/entity (:db/id file))
        file (:file/path file)
        token (get-github-token)]
    (when-let [content (db/get-file-content repo-url file)]
      (let [encoded-content (utf8/encode content)
            content' (str (utf8/substring encoded-content 0 pos)
                          (-> (utf8/substring encoded-content pos)
                              (string/replace-first "DONE" "TODO")))]
        (alter-file repo-url file content' nil)))))

(defn git-set-username-email!
  [repo-url {:keys [name email]}]
  (when (and name email)
    (git/set-username-email
     (git/get-repo-dir repo-url)
     name
     email)))

(defn set-route-match!
  [route]
  (swap! state/state assoc :route-match route)
  (when-let [fragment (util/get-fragment)]
    (util/scroll-to-element fragment)))

(defn set-ref-component!
  [k ref]
  (swap! state/state assoc :ref-components k ref))

(defn periodically-push-tasks
  [repo-url]
  (let [token (get-github-token)
        push (fn []
               (push repo-url))]
    (js/setInterval push
                    (* (config/git-push-secs) 1000))))

(defn periodically-pull-and-push
  [repo-url {:keys [pull-now?]
             :or {pull-now? true}}]
  ;; (periodically-pull repo-url pull-now?)
  ;; (periodically-push-tasks repo-url)
  (when-not config/dev?
    (periodically-pull repo-url pull-now?)
    (periodically-push-tasks repo-url))
  )

(defn render-local-images!
  []
  (when-let [content-node (gdom/getElement "content")]
    (let [images (array-seq (gdom/getElementsByTagName "img" content-node))
          get-src (fn [image] (.getAttribute image "src"))
          local-images (filter
                        (fn [image]
                          (let [src (get-src image)]
                            (and src
                                 (not (or (string/starts-with? src "http://")
                                          (string/starts-with? src "https://"))))))
                        images)]
      (doseq [img local-images]
        (gobj/set img
                  "onerror"
                  (fn []
                    (gobj/set (gobj/get img "style")
                              "display" "none")))
        (let [path (get-src img)
              path (if (= (first path) \.)
                     (subs path 1)
                     path)]
          (util/p-handle
           (fs/read-file-2 (git/get-repo-dir (state/get-current-repo))
                           path)
           (fn [blob]
             (let [blob (js/Blob. (array blob) (clj->js {:type "image"}))
                   img-url (image/create-object-url blob)]
               (gobj/set img "src" img-url)
               (gobj/set (gobj/get img "style")
                         "display" "initial")))))))))

(defn load-more-journals!
  []
  (let [current-length (:journals-length @state/state)]
    (when (< current-length (db/get-journals-length))
      (state/update-state! :journals-length inc))))

(defn request-presigned-url
  [file filename mime-type url-handler]
  (cond
    (> (gobj/get file "size") (* 5 1024 1024))
    (show-notification! [:p "Sorry, we don't support any file that's larger than 5MB."] :error)

    :else
    (util/post (str config/api "presigned_url")
               {:filename filename
                :mime-type mime-type}
               (fn [{:keys [presigned-url s3-object-key] :as resp}]
                 (if presigned-url
                   (util/upload presigned-url
                                file
                                (fn [_result]
                                  ;; request cdn signed url
                                  (util/post (str config/api "signed_url")
                                             {:s3-object-key s3-object-key}
                                             (fn [{:keys [signed-url]}]
                                               (if signed-url
                                                 (do
                                                   (url-handler signed-url))
                                                 (prn "Something error, can't get a valid signed url.")))
                                             (fn [error]
                                               (prn "Something error, can't get a valid signed url."))))
                                (fn [error]
                                  (prn "upload failed.")
                                  (js/console.dir error)))
                   ;; TODO: notification, or re-try
                   (prn "failed to get any presigned url, resp: " resp)))
               (fn [_error]
                 ;; (prn "Get token failed, error: " error)
                 ))))

(defn set-me-if-exists!
  []
  (when js/window.user
    (when-let [me (bean/->clj js/window.user)]
      (set-state-kv! :me me)
      me)))

(defn sign-out!
  [e]
  (p/let [_idb-clear (js/window.pfs._idb.wipe)]
    (js/localStorage.clear)
    (set! (.-href js/window.location) "/logout")))

(defn set-format-js-loading!
  [format value]
  (when format
    (swap! state/state assoc-in [:format/loading format] value)))

(defn lazy-load
  [format]
  (let [format (format/normalize format)]
    (when-let [record (format/get-format-record format)]
      (when-not (protocol/loaded? record)
        (set-format-js-loading! format true)
        (protocol/lazyLoad record
                           (fn [result]
                             (set-format-js-loading! format false)))))))

(defn reset-cursor-range!
  [node]
  (when node
    (state/set-cursor-range! (util/caret-range node))))

(defn restore-cursor-pos!
  ([id markup]
   (restore-cursor-pos! id markup false))
  ([id markup dummy?]
   (when-let [node (gdom/getElement (str id))]
     (when-let [range (string/trim (state/get-cursor-range))]
       (let [pos (inc (diff/find-position markup range))
             pos (if dummy? (+ 3 pos) pos)]
         (util/set-caret-pos! node pos))))))

(defn remove-slash!
  []
  (when-let [edit-content (state/get-edit-content)]
    (when (= \/ (last edit-content))
      (let [new-value (subs edit-content 0 (dec (count edit-content)))]
        (state/set-edit-content! new-value)))))

(defn search
  [q]
  (swap! state/state assoc :search/result (search/search q)))

(defn clear-search!
  []
  (swap! state/state assoc
         :search/result nil
         :search/q ""))

(defn email? [v]
  (and v
       (.isValid (EmailAddress. v))))

(defn set-email!
  [email]
  (when (email? email)
    (util/post (str config/api "email")
               {:email email}
               (fn [result]
                 (db/transact! [{:me/email email}])
                 (swap! state/state assoc-in [:me :email] email))
               (fn [error]
                 (show-notification! "Email already exists!"
                                     :error)))))


(defn new-file-content
  [{:heading/keys [content meta dummy?] :as heading} file-content value]
  (let [utf8-content (utf8/encode file-content)
        prefix (utf8/substring utf8-content 0 (:pos meta))
        postfix (let [end-pos (if dummy?
                                (:pos meta)
                                (:end-pos meta))]
                  (utf8/substring utf8-content end-pos))
        value (str
               (if (= "\n" (last prefix))
                 ""
                 "\n")
               value
               (if (= "\n" (first postfix))
                 ""
                 "\n"))]
    [(str prefix value postfix)
     value]))

(defn save-heading-if-changed!
  [{:heading/keys [uuid content meta file dummy?] :as heading} value]
  (let [repo (state/get-current-repo)
        value (string/trim value)]
    (when (not= (string/trim content) value) ; heading content changed
      (let [file-id (:db/id file)
            file (db/entity file-id)
            file-content (:file/content file)
            file-path (:file/path file)
            format (format/get-format file-path)
            [new-content value] (new-file-content heading file-content value)
            after-headings (db/get-file-after-headings repo file-id (get meta :end-pos))
            {:keys [headings pages start-pos end-pos]} (block/parse-heading (assoc heading :heading/content value) format)
            last-start-pos (atom end-pos)
            after-headings (mapv
                            (fn [{:heading/keys [uuid meta] :as heading}]
                              (let [old-start-pos (:pos meta)
                                    old-end-pos (:end-pos meta)
                                    new-end-pos (if old-end-pos
                                                  (+ @last-start-pos (- old-end-pos old-start-pos)))
                                    new-meta {:pos @last-start-pos
                                              :end-pos new-end-pos}]
                                (reset! last-start-pos new-end-pos)
                                {:heading/uuid uuid
                                 :heading/meta new-meta}))
                            after-headings)]
        (db/transact!
          (concat
           pages
           headings
           after-headings
           [{:file/path file-path
             :file/content new-content}]))
        (alter-file repo
                    file-path
                    new-content
                    {:reset? false})))))

(defn delete-heading!
  [{:heading/keys [uuid meta content file] :as heading} dummy?]
  (when-not dummy?
    (let [repo (state/get-current-repo)
          file-path (:file/path (db/entity (:db/id file)))
          file-content (:file/content (db/entity (:db/id file)))
          after-headings (db/get-file-after-headings repo (:db/id file) (:end-pos meta))
          last-start-pos (atom (:pos meta))
          updated-headings (mapv
                            (fn [{:heading/keys [uuid meta] :as heading}]
                              (let [old-start-pos (:pos meta)
                                    old-end-pos (:end-pos meta)
                                    new-end-pos (if old-end-pos
                                                  (+ @last-start-pos (- old-end-pos old-start-pos)))
                                    new-meta {:pos @last-start-pos
                                              :end-pos new-end-pos}]
                                (reset! last-start-pos new-end-pos)
                                {:heading/uuid uuid
                                 :heading/meta new-meta}))
                            after-headings)
          new-content (utf8/delete! file-content (:pos meta) (:end-pos meta))]
      (db/transact!
        (concat
         [[:db.fn/retractEntity [:heading/uuid uuid]]]
         updated-headings
         [{:file/path file-path
           :file/content new-content}]))
      (alter-file repo
                  file-path
                  new-content
                  {:reset? false})
      )))

(defn clone-and-pull
  [repo-url]
  (p/then (clone repo-url)
          (fn []
            (git-set-username-email! repo-url (:me @state/state))
            (load-db-and-journals! repo-url nil true)
            (periodically-pull-and-push repo-url {:pull-now? false}))))

(defn star-page!
  [page-name starred?]
  (state/star-page! (state/get-current-repo) page-name starred?))

(defn remove-repo!
  [{:keys [id url] :as repo}]
  (util/delete (str config/api "repos/" id)
               (fn []
                 (db/remove-conn! url)
                 (storage/remove (db/datascript-db url))
                 (state/delete-repo! repo)
                 ;; TODO: clear indexdb
                 )
               (fn [error]
                 (prn "Delete repo failed, error: " error))))

(defn rebuild-index!
  [{:keys [id url] :as repo}]
  (db/remove-conn! url)
  (storage/remove (db/datascript-db url))
  (clone-and-pull url))

(defn watch-config!
  []
  (add-watch state/state
             :config-changed
             (fn [_k _r old-state new-state]
               (let [repos (seq (keys (:config new-state)))]
                 (doseq [repo repos]
                   (when (not= (get-in new-state [:config repo])
                               (get-in old-state [:config repo]))
                     ;; persistent to file
                     (let [repo-dir (git/get-repo-dir repo)
                           file-path (str "/" config/config-file)
                           content (js/JSON.stringify (bean/->js (get-in new-state [:config repo]))
                                                      nil
                                                      ;; indent spacing level
                                                      2)]
                       (p/let [content' (load-file repo file-path)]
                         (when (not= content content')
                           (fs/write-file repo-dir file-path content)
                           (db/reset-file! repo config/config-file content)
                           (git-add repo config/config-file))))))))))

(defn edit-heading!
  [heading-id prev-pos]
  (let [heading (or
                 (db/entity [:heading/uuid heading-id])
                 ;; dummy?
                 {:heading/uuid heading-id
                  :heading/content "** "})]
    (let [{:heading/keys [content]} heading
          edit-input-id (str "edit-heading-" heading-id)
          content-length (count content)
          text-range (if (or (= :max prev-pos) (<= content-length prev-pos))
                       content
                       (subs content 0 prev-pos))]
      (state/set-cursor-range! text-range)
      (state/set-edit-input-id! edit-input-id))))

(defn start!
  []
  (let [{:keys [repos] :as me} (set-me-if-exists!)]
    (db/restore! me db-listen-to-tx!)
    (doseq [{:keys [id url]} repos]
      (let [repo url]
        (if (db/cloned? repo)
          (do
            (git-set-username-email! repo me)
            (periodically-pull-and-push repo {:pull-now? true}))
          (clone-and-pull repo))))
    (watch-config!)))

(comment

  (defn debug-latest-commits
    []
    (get-latest-commit (state/get-current-repo)
                       (fn [commits]
                         (prn (mapv :oid (bean/->clj commits))))
                       3))

  (defn debug-matrix
    []
    (p/let [changes (git/get-status-matrix (state/get-current-repo))]
      (prn changes)))

  ;; (defn debug-file-and-headings
  ;;   [path]
  ;;   (p/let [content (load-file (state/get-current-repo)
  ;;                              path)]
  ;;     (let [db-content (db/get-file path)
  ;;           headings (db/get-file-by-concat-headings-debug-version path)]
  ;;       (prn {:content content
  ;;             :utf8-length (utf8/length (utf8/encode content))
  ;;             :headings headings}))))

  ;; (debug-file-and-headings "readme.org")
  )
