(ns banking.repositories
  "Imperative shell: owns the bank -- the global, in-memory store that stands in
   for the database -- and is the only place it is mutated.

   The bank is an atom holding a ledger (a map of account-id -> account). You
   read the current ledger, manipulate it however you like with the account
   operations (threading it through them, in the functional core), and the bank
   only changes when you `commit` the result.

   The repository knows NOTHING about which operations were run. It just:
     - hands out the current ledger (`ledger`), and
     - on `commit`, reflects a result into the bank -- writing the new ledger
       only when the result carries a :persist effect, and touching nothing on
       failure.

   Reflecting the whole ledger is enough for this single-threaded, in-memory
   model; a real external store would use the :persist payloads to write the
   individual account/transaction records, and concurrent writers would call for
   a compare-and-set! retry loop.")

(defonce ^{:doc "The global in-memory bank: an atom holding the current ledger."}
  bank
  (atom {}))

(defn reset-bank!
  "Empty the bank. Intended for test isolation between runs."
  []
  (reset! bank {}))

(defn ledger
  "The bank's current ledger -- a snapshot to read and manipulate."
  []
  @bank)

(defn- persist?
  "True when `effects` contains a :persist instruction."
  [effects]
  (boolean (some (fn [[verb]] (= verb :persist)) effects)))

(defn commit
  "Reflect the outcome of a ledger manipulation into the bank. `result` is what
   an operation (or a chain of them) produced: either {:error ...}, or
   {:ledger ... :value ... :effects ...}.

   On failure, the bank is left untouched and the error is returned. Otherwise
   the ledger is written into the bank -- but only when a :persist effect is
   present -- and the :value is returned."
  [result]
  (if (:error result)
    result
    (let [{:keys [ledger effects value]} result]
      (when (persist? effects)
        (reset! bank ledger))
      value)))
