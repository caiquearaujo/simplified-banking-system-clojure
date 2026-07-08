(ns banking.accounts
  "Functional core: the account decisions. Each is a pure function of the
   current ledger plus its inputs, returning either

     success  =>  {:ledger <new-ledger> :value <v> :effects [<effect> ...]}
     failure  =>  {:error <reason-keyword>}

   Nothing here mutates -- a decision derives a new ledger and describes the
   side effects it wants as data, without running them. Publishing the new
   ledger and running the effects is the imperative shell's job, over in
   `banking.repositories`.

   An effect is a tuple [verb entity payload]. For now the only verb is
   :persist, with entity :account (on creation) or :transaction (on movements):

     [:persist :account <account>]
     [:persist :transaction <txn>]

   The ledger is a map of account-id -> account. The numeric transaction :id
   from the original design is still left out: no test drives it and its
   uniqueness source is undecided."
  (:require [banking.validations :as validations]))

(defn- new-account
  "A fresh, empty account."
  [id timestamp]
  {:id id :balance 0 :inflow 0 :outflow 0 :transactions [] :created-at timestamp})

(defn- transaction
  "An inflow/outflow movement against `account-id`."
  [account-id type amount timestamp]
  {:account-id account-id
   :type type
   :amount amount
   :created-at timestamp})

(defn- apply-transaction
  "Append `txn` to `account`, recomputing the cached balance and the matching
   inflow/outflow total. The transaction's :type is :inflow or :outflow -- which
   also happens to be the cache key it feeds."
  [account {:keys [type amount] :as txn}]
  (-> account
      (update :transactions conj txn)
      (update type + amount)
      (update :balance (if (= type :inflow) + -) amount)))

(defn- movement
  "Shared core of deposit/debit once the amount and account are validated:
   append a `type` transaction to `account`, and describe its persistence."
  [ledger account-id account type amount timestamp]
  (let [txn (transaction account-id type amount timestamp)
        updated (apply-transaction account txn)]
    {:ledger (assoc ledger account-id updated)
     :value (:balance updated)
     :effects [[:persist :transaction txn]]}))

(defn create-account
  "Create a fresh, empty account under `account-id`. Fails if the id is taken."
  [ledger account-id timestamp]
  (if (contains? ledger account-id)
    {:error :account-already-exists}
    (let [account (new-account account-id timestamp)]
      {:ledger (assoc ledger account-id account)
       :value account-id
       :effects [[:persist :account account]]})))

(defn deposit
  "Add `amount` to `account-id` as an inflow. Fails if the amount is not a
   positive integer or the account is unknown."
  [ledger account-id amount timestamp]
  (if-not (validations/valid-amount? amount)
    {:error :invalid-amount}
    (if-let [account (get ledger account-id)]
      (movement ledger account-id account :inflow amount timestamp)
      {:error :account-not-found})))

(defn debit
  "Remove `amount` from `account-id` as an outflow. Fails if the amount is not a
   positive integer, the account is unknown, or its balance does not cover the
   amount."
  [ledger account-id amount timestamp]
  (if-not (validations/valid-amount? amount)
    {:error :invalid-amount}
    (if-let [account (get ledger account-id)]
      (if (< (:balance account) amount)
        {:error :insufficient-balance}
        (movement ledger account-id account :outflow amount timestamp))
      {:error :account-not-found})))

(defn transfer
  "Move `amount` from `source-id` to `target-id`: debit the source, then deposit
   into the target, propagating the first failure if any step fails. The value
   is the source's new balance, and the effects are the debit's followed by the
   deposit's."
  [ledger source-id target-id amount timestamp]
  (cond
    (not (validations/valid-amount? amount))
    {:error :invalid-amount}

    (not (validations/distinct-accounts? source-id target-id))
    {:error :same-account}

    :else
    (let [debited (debit ledger source-id amount timestamp)]
      (if (:error debited)
        debited

        (let [deposited (deposit (:ledger debited) target-id amount timestamp)]
          (if (:error deposited)
            deposited

            {:ledger (:ledger deposited)
             :value (:value debited)
             :effects (into (:effects debited) (:effects deposited))}))))))
