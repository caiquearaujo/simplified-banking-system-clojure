(ns banking.validations
  "Edge validations: pure predicates over the inputs alone. They never look at
   account state, so they can run at the very edge and reject bad input before
   any decision touches the repository.")

(defn valid-amount?
  "True when `amount` is a positive integer. Anything else -- zero, a negative,
   a decimal, or a non-number such as nil or a string -- is rejected."
  [amount]
  (and (integer? amount)
       (pos? amount)))

(defn distinct-accounts?
  "True when `source` and `target` identify two different accounts."
  [source target]
  (not= source target))
