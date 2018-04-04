(ns alda.sound
  (:require [alda.sound.midi :as    midi]
            [alda.util       :refer (parse-time
                                     pdoseq-block
                                     parse-position)]
            [taoensso.timbre :as    log])
  (:import [javax.sound.midi
            Sequence MidiSystem MidiDevice
            ShortMessage Synthesizer MetaMessage]
           [java.io File]))

(defn new-audio-context
  []
  (atom
    {:audio-types #{}
     :midi-sequencer (midi/new-midi-sequencer)}))

(defn set-up?
  [{:keys [audio-context] :as score} audio-type]
  (contains? (:audio-types @audio-context) audio-type))

(defmulti set-up-audio-type!
  (fn [score audio-type] audio-type))

(defmethod set-up-audio-type! :default
  [score audio-type]
  (log/errorf "No implementation of set-up-audio-type! defined for type %s"
              audio-type))

(defmethod set-up-audio-type! :midi
  [{:keys [audio-context] :as score} _]
  (log/debug "Setting up MIDI...")
  (midi/get-midi-synth! audio-context))

(declare determine-audio-types)

(defn set-up!
  "Does any necessary setup for one or more audio types.
   e.g. for MIDI, create and open a MIDI synth."
  ([score]
   (set-up! score (determine-audio-types score)))
  ([{:keys [audio-context] :as score} audio-type]
   (if (coll? audio-type)
     (pdoseq-block [a-t audio-type]
       (set-up! score a-t))
     (when-not (set-up? score audio-type)
       (swap! audio-context update :audio-types conj audio-type)
       (set-up-audio-type! score audio-type)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti refresh-audio-type!
  (fn [score audio-type] audio-type))

(defmethod refresh-audio-type! :default
  [score audio-type]
  (log/errorf "No implementation of refresh-audio-type! defined for type %s"
              audio-type))

(defmethod refresh-audio-type! :midi
  [{:keys [audio-context] :as score} _]
  (midi/load-instruments! audio-context score))

(defn refresh!
  "Performs any actions that may be needed each time the `play!` function is
   called. e.g. for MIDI, load instruments into channels (this needs to be
   done every time `play!` is called because new instruments may have been
   added to the score between calls to `play!`, when using Alda live.)"
  ([score]
    (pdoseq-block [audio-type (determine-audio-types score)]
      (refresh! score audio-type)))
  ([score audio-type]
   (if (coll? audio-type)
     (pdoseq-block [a-t audio-type]
       (refresh! score a-t))
     (when (set-up? score audio-type)
       (refresh-audio-type! score audio-type)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti tear-down-audio-type!
  (fn [score audio-type] audio-type))

(defmethod tear-down-audio-type! :default
  [score audio-type]
  (log/errorf "No implementation of tear-down! defined for type %s" audio-type))

(defmethod tear-down-audio-type! :midi
  [{:keys [audio-context] :as score} _]
  (log/debug "Closing MIDI synth...")
  (midi/close-midi-synth! audio-context))

(defn tear-down!
  "Completely clean up after a score.

   Playback may not necessarily be resumed after doing this."
  ([{:keys [audio-context] :as score}]
   (-> @audio-context :midi-sequencer .close)
   ;; Do any necessary clean-up for each audio type.
   ;; e.g. for MIDI, close the MidiSynthesizer.
   (tear-down! score (determine-audio-types score)))
  ([{:keys [audio-context] :as score} audio-type]
   (if (coll? audio-type)
     (pdoseq-block [a-t audio-type]
       (tear-down! score a-t))
     (when (set-up? score audio-type)
       (swap! audio-context update :audio-types disj audio-type)
       (tear-down-audio-type! score audio-type)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti stop-playback-for-audio-type!
  (fn [score audio-type] audio-type))

(defmethod stop-playback-for-audio-type! :default
  [score audio-type]
  (log/errorf
    "No implementation of stop-playback-for-audiotype! defined for type %s"
    audio-type))

(defmethod stop-playback-for-audio-type! :midi
  [{:keys [audio-context] :as score} _]
  (log/debug "Stopping MIDI playback...")
  (midi/all-sound-off! audio-context))

(defn stop-playback!
  "Stop playback, but leave the score in a state where playback can be resumed."
  ([{:keys [audio-context] :as score}]
   (-> @audio-context :midi-sequencer .close)
   (stop-playback! score (determine-audio-types score)))
  ([{:keys [audio-context] :as score} audio-type]
   (if (coll? audio-type)
     (pdoseq-block [a-t audio-type]
       (stop-playback! score a-t))
     (when (set-up? score audio-type)
       (stop-playback-for-audio-type! score audio-type)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti start-event!
  "Kicks off a note/event, using the appropriate method based on the type of the
   instrument."
  (fn [audio-ctx event instrument]
    (-> instrument :config :type)))

(defmethod start-event! :default
  [_ _ instrument]
  (log/errorf "No implementation of start-event! defined for type %s"
              (-> instrument :config :type)))

(defmethod start-event! nil
  [_ _ _]
  :do-nothing)

(defmethod start-event! :midi
  [audio-ctx note _]
  (midi/play-note! audio-ctx note))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti stop-event!
  "Ends a note/event, using the appropriate method based on the type of the
   instrument."
  (fn [audio-ctx event instrument]
    (-> instrument :config :type)))

(defmethod stop-event! :default
  [_ _ instrument]
  (log/errorf "No implementation of start-event! defined for type %s"
              (-> instrument :config :type)))

(defmethod stop-event! nil
  [_ _ _]
  :do-nothing)

(defmethod stop-event! :midi
  [audio-ctx note _]
  (midi/stop-note! audio-ctx note))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- score-length
  "Given an event set from a score, calculates the length of the score in ms."
  [event-set]
  (let [events   (filter :duration event-set)
        note-end (fn [{:keys [offset duration] :as note}]
                   (+ offset duration))]
    (if (and events (not (empty? events)))
      (apply max (map note-end events))
      0)))

(defn determine-audio-types
  [{:keys [instruments] :as score}]
  (set (for [[id {:keys [config]}] instruments]
         (:type config))))

(def ^:dynamic *play-opts* {})

(defmacro with-play-opts
  "Apply `opts` as overrides to *play-opts* when executing `body`"
  [opts & body]
  `(binding [*play-opts* (merge *play-opts* ~opts)]
     ~@body))

(defn- lookup-time [markers pos]
  (let [pos (if (string? pos)
              (parse-position pos)
              pos)]
    (cond (keyword? pos)
          (or (markers (name pos))
              (throw (Exception. (str "Marker " pos " not found."))))

          (or (number? pos) (nil? pos))
          pos

          :else
          (throw (Exception.
                   (str "Do not support " (type pos) " as a play time."))))))

(defn start-finish-times [{:keys [from to]} markers]
  (map (partial lookup-time markers) [from to]))

(defn earliest-offset
  [event-set]
  (->> (map :offset event-set)
       (apply min Long/MAX_VALUE)
       (max 0)))

(defn shift-events
  [events offset cut-off]
  (let [offset  (or offset 0)
        cut-off (when cut-off (- cut-off offset))
        keep?   (if cut-off
                  #(and (<= 0 %) (> cut-off %))
                  #(<= 0 %))]
    (->> (sequence (comp (map #(update-in % [:offset] - offset))
                         (filter (comp keep? :offset)))
                   events)
         (sort-by :offset))))

(defn score-to-sequence
  [events score]
  (let [{:keys [instruments audio-context]} score
        {:keys [midi-synth midi-channels]} @audio-context
        channels  (.getChannels ^Synthesizer midi-synth)

        ;; TODO make resolution configurable
        seq (new Sequence Sequence/PPQ Sequence/SMPTE_24)
        sequencer (doto (MidiSystem/getSequencer false) .open)
        receiver (.getReceiver sequencer)
        currentTrack (.createTrack seq)]
    ;; warm up the recorder
    (doto sequencer
      (.setSequence seq)
      (.setTickPosition 0)
      (.recordEnable currentTrack -1)
      (.startRecording))
    ;; Pipe events into recorder
    (midi/load-instruments! nil score receiver)
    (doseq [{:keys [offset instrument duration midi-note volume] :as event} events]
      (let
          [volume (* 127 volume)
           channel-number (-> instrument midi-channels :channel)

           playMessage (doto (new ShortMessage)
                         (.setMessage ShortMessage/NOTE_ON channel-number midi-note
                                      volume))
           stopMessage (when-not (:function event)
                         (doto (new ShortMessage)
                           (.setMessage ShortMessage/NOTE_OFF channel-number midi-note
                                        volume)))
           offset (* offset 1000)
           duration (* duration 1000)]
        (.send receiver playMessage offset)
        (when stopMessage
          (.send receiver stopMessage (+ offset duration)))))

    ;; auto-add a end of track metamessage
    (.send receiver
           (doto (new MetaMessage)
             (.setMessage midi/MIDI-END-OF-TRACK nil 0)) -1)

    ;; Stop our recorder
    (doto sequencer
      .stopRecording
      .close)
    ;; Write out!
    ;; TODO make this into a proper command
    ;; (MidiSystem/write seq 0 (new File "test.mid"))
    seq))

(defn play!
  "Plays an Alda score, optionally from given start/end marks determined by
   *play-opts*.

   Optionally takes as a second argument a set of events to play (which could
   be pre-filtered, e.g. for playing only a portion of the score).

   In either case, the offsets of the events to be played are shifted back such
   that the earliest event's offset is 0 -- this is so that playback will start
   immediately.

   Returns a result map containing the following values:

     :score    The full score being played.

     :stop!    A function that, when called mid-playback, will stop any further
               events from playing.

     :wait     A function that will sleep for the duration of the score. This is
               useful if you want to playback asynchronously, perform some
               actions, then wait until playback is complete before proceeding."
  [score & [event-set]]
  (let [{:keys [one-off? async?]} *play-opts*
        _           (log/debug "Determining audio types...")
        score       (update score :audio-context #(or % (new-audio-context)))
        _           (log/debug "Setting up audio types...")
        _           (set-up! score)
        _           (refresh! score)
        playing?    (atom true)
        wait        (promise)
        _           (log/debug "Determining events to schedule...")
        [start end] (start-finish-times *play-opts* (:markers score))
        start'      (if event-set
                      (earliest-offset event-set)
                      start)
        events      (-> (or event-set (:events score))
                        (shift-events start' end))]
    (log/debug "Scheduling events...")
    (midi/play-sequence!
      (-> score :audio-context deref :midi-sequencer)
      (score-to-sequence events score)
      #(deliver wait :done))
    (cond
      (and one-off? async?)       (future @wait (tear-down! score))
      (and one-off? (not async?)) (do @wait (tear-down! score))
      (not async?)                @wait)
    {:score score
     :stop! #(do
               (reset! playing? false)
               (if one-off?
                 (tear-down! score)
                 (stop-playback! score)))
     :wait  #(deref wait)}))
