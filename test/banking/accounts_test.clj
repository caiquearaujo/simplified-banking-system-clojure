(ns banking.accounts-test
  "TDD specs for the functional core (decisions). Every decision is pure: it
   takes the current repository plus inputs and returns either

     success  =>  {:repository <new-repo> :value <v> :effects [<effect> ...]}
     failure  =>  {:error <reason-keyword>}

   The repository is modelled as a plain map of account-id -> account, an
   account carries {:id :balance :inflow :outflow :transactions :created-at},
   and a transaction carries {:id :account-id :type :amount :created-at} with
   :type being :inflow or :outflow. Transactions are appended newest-last, and
   a transfer stamps both of its legs with the same timestamp. The transaction
   :id is left unasserted here on purpose -- its uniqueness source is still an
   open design question. These shapes -- and the :error reasons below -- are
   the contract these tests lock in; if the design shifts, rename them here in
   one place.

   Each test spells out its sequence of real operations top to bottom: setup
   steps that must succeed go through `step`, and the operation under test is
   called directly so its result can be inspected. Every operation draws its
   timestamp from `tick`, so no two operations share a timestamp anywhere in
   the suite."
  (:require [clojure.test :refer [deftest testing is]]
            [matcher-combinators.test :refer [match?]]
            [banking.fixtures :refer [tick]]
            [banking.accounts :as accounts]))

;; --- fixtures -------------------------------------------------------------

(defn- step
  "Apply a decision as a setup step and return the resulting repository,
   failing loudly if the step itself did not succeed."
  [repo f & args]
  (let [{:keys [repository error]} (apply f repo args)]
    (assert (nil? error) (str "setup step failed: " error))
    repository))

;; --- U01 : create new accounts -------------------------------------------

(deftest create-account
  (testing "creating with a fresh id succeeds"
    (let [{:keys [value repository error]} (accounts/create-account {} "acc-1" (tick))]
      (is (nil? error))
      (is (= "acc-1" value))
      (is (some? (get repository "acc-1")))))

  (testing "the new account starts empty (zero balance, no history)"
    (let [{:keys [repository]} (accounts/create-account {} "acc-1" (tick))]
      (is (match? {:id "acc-1" :balance 0 :inflow 0 :outflow 0 :transactions []}
                  (get repository "acc-1")))))

  (testing "emits a persist effect carrying the new account"
    (let [{:keys [effects]} (accounts/create-account {} "acc-1" (tick))]
      (is (match? [[:persist :account {:id "acc-1" :balance 0 :inflow 0 :outflow 0 :transactions []}]]
                  effects))))

  (testing "creating with an already-used id fails"
    (let [repo   (step {} accounts/create-account "acc-1" (tick))
          result (accounts/create-account repo "acc-1" (tick))]
      (is (match? {:error :account-already-exists} result))))

  (testing "the failed re-creation leaves the existing account untouched"
    (let [repo   (-> {}
                     (step accounts/create-account "acc-1" (tick))
                     (step accounts/deposit "acc-1" 100 (tick)))
          result (accounts/create-account repo "acc-1" (tick))]
      (is (match? {:error :account-already-exists} result))
      (is (nil? (:repository result)))
      (is (= 100 (:balance (get repo "acc-1")))))))

;; --- U02 : deposit money into accounts -----------------------------------

(deftest deposit
  (testing "depositing into an existing account increases its balance by the amount"
    (let [repo (-> {}
                   (step accounts/create-account "acc-1" (tick))
                   (step accounts/deposit "acc-1" 100 (tick)))
          {:keys [repository]} (accounts/deposit repo "acc-1" 50 (tick))]
      (is (= 150 (:balance (get repository "acc-1"))))
      (is (= 150 (:inflow (get repository "acc-1"))))))

  (testing "the returned balance reflects the new amount"
    (let [repo (step {} accounts/create-account "acc-1" (tick))
          {:keys [value]} (accounts/deposit repo "acc-1" 80 (tick))]
      (is (= 80 value))))

  (testing "records an inflow transaction on each deposit, in order"
    (let [repo      (step {} accounts/create-account "acc-1" (tick))
          first-ts  (tick)
          repo      (step repo accounts/deposit "acc-1" 100 first-ts)
          second-ts (tick)
          {:keys [repository]} (accounts/deposit repo "acc-1" 50 second-ts)]
      (is (match? [{:account-id "acc-1" :type :inflow :amount 100 :created-at first-ts}
                   {:account-id "acc-1" :type :inflow :amount 50  :created-at second-ts}]
                  (:transactions (get repository "acc-1"))))))

  (testing "emits a persist effect carrying the inflow transaction"
    (let [repo (step {} accounts/create-account "acc-1" (tick))
          ts   (tick)
          {:keys [effects]} (accounts/deposit repo "acc-1" 100 ts)]
      (is (match? [[:persist :transaction {:account-id "acc-1" :type :inflow :amount 100 :created-at ts}]]
                  effects))))

  (testing "depositing an invalid amount fails"
    (let [repo (step {} accounts/create-account "acc-1" (tick))]
      (is (match? {:error :invalid-amount} (accounts/deposit repo "acc-1" 0 (tick))))
      (is (match? {:error :invalid-amount} (accounts/deposit repo "acc-1" -50 (tick))))))

  (testing "depositing into a non-existing account fails"
    (is (match? {:error :account-not-found}
                (accounts/deposit {} "ghost" 50 (tick))))))

;; --- debit : building block of transfer, first-class API too -------------

(deftest debit
  (testing "debiting an existing account reduces its balance by the amount"
    (let [repo (-> {}
                   (step accounts/create-account "acc-1" (tick))
                   (step accounts/deposit "acc-1" 100 (tick)))
          {:keys [value repository]} (accounts/debit repo "acc-1" 40 (tick))]
      (is (= 60 value))
      (is (= 60 (:balance (get repository "acc-1"))))
      (is (= 40 (:outflow (get repository "acc-1"))))))

  (testing "records an outflow transaction, keeping the prior history in order"
    (let [repo     (-> {}
                       (step accounts/create-account "acc-1" (tick))
                       (step accounts/deposit "acc-1" 100 (tick)))
          debit-ts (tick)
          {:keys [repository]} (accounts/debit repo "acc-1" 40 debit-ts)]
      (is (match? [{:account-id "acc-1" :type :inflow  :amount 100}
                   {:account-id "acc-1" :type :outflow :amount 40 :created-at debit-ts}]
                  (:transactions (get repository "acc-1"))))))

  (testing "debiting more than the balance fails and yields no new repository"
    (let [repo   (-> {}
                     (step accounts/create-account "acc-1" (tick))
                     (step accounts/deposit "acc-1" 30 (tick)))
          result (accounts/debit repo "acc-1" 100 (tick))]
      (is (match? {:error :insufficient-balance} result))
      (is (nil? (:repository result)))))

  (testing "emits a persist effect carrying the outflow transaction"
    (let [repo (-> {}
                   (step accounts/create-account "acc-1" (tick))
                   (step accounts/deposit "acc-1" 100 (tick)))
          ts   (tick)
          {:keys [effects]} (accounts/debit repo "acc-1" 40 ts)]
      (is (match? [[:persist :transaction {:account-id "acc-1" :type :outflow :amount 40 :created-at ts}]]
                  effects))))

  (testing "debiting an invalid amount fails"
    (let [repo (-> {}
                   (step accounts/create-account "acc-1" (tick))
                   (step accounts/deposit "acc-1" 100 (tick)))]
      (is (match? {:error :invalid-amount} (accounts/debit repo "acc-1" 0 (tick))))
      (is (match? {:error :invalid-amount} (accounts/debit repo "acc-1" -20 (tick))))))

  (testing "debiting a non-existing account fails"
    (is (match? {:error :account-not-found}
                (accounts/debit {} "ghost" 10 (tick))))))

;; --- U03 : transfer money between two accounts ---------------------------

(deftest transfer
  (testing "transferring moves the amount from source to target"
    (let [repo (-> {}
                   (step accounts/create-account "acc-a" (tick))
                   (step accounts/create-account "acc-b" (tick))
                   (step accounts/deposit "acc-a" 100 (tick)))
          {:keys [repository]} (accounts/transfer repo "acc-a" "acc-b" 40 (tick))]
      (is (= 60 (:balance (get repository "acc-a"))))
      (is (= 40 (:balance (get repository "acc-b"))))))

  (testing "the returned value is the source's new balance"
    (let [repo (-> {}
                   (step accounts/create-account "acc-a" (tick))
                   (step accounts/create-account "acc-b" (tick))
                   (step accounts/deposit "acc-a" 100 (tick)))
          {:keys [value]} (accounts/transfer repo "acc-a" "acc-b" 40 (tick))]
      (is (= 60 value))))

  (testing "the total across both accounts is unchanged by a transfer"
    (let [repo (-> {}
                   (step accounts/create-account "acc-a" (tick))
                   (step accounts/create-account "acc-b" (tick))
                   (step accounts/deposit "acc-a" 100 (tick)))
          before (+ (:balance (get repo "acc-a"))
                    (:balance (get repo "acc-b")))
          {:keys [repository]} (accounts/transfer repo "acc-a" "acc-b" 40 (tick))
          after (+ (:balance (get repository "acc-a"))
                   (:balance (get repository "acc-b")))]
      (is (= before after))))

  (testing "records an outflow on the source and an inflow on the target"
    (let [repo        (-> {}
                          (step accounts/create-account "acc-a" (tick))
                          (step accounts/create-account "acc-b" (tick))
                          (step accounts/deposit "acc-a" 100 (tick)))
          transfer-ts (tick)
          {:keys [repository]} (accounts/transfer repo "acc-a" "acc-b" 40 transfer-ts)]
      (is (match? [{:account-id "acc-a" :type :inflow  :amount 100}
                   {:account-id "acc-a" :type :outflow :amount 40 :created-at transfer-ts}]
                  (:transactions (get repository "acc-a"))))
      (is (match? [{:account-id "acc-b" :type :inflow :amount 40 :created-at transfer-ts}]
                  (:transactions (get repository "acc-b"))))))

  (testing "emits a persist effect for each leg, source then target"
    (let [repo (-> {}
                   (step accounts/create-account "acc-a" (tick))
                   (step accounts/create-account "acc-b" (tick))
                   (step accounts/deposit "acc-a" 100 (tick)))
          ts   (tick)
          {:keys [effects]} (accounts/transfer repo "acc-a" "acc-b" 40 ts)]
      (is (match? [[:persist :transaction {:account-id "acc-a" :type :outflow :amount 40 :created-at ts}]
                   [:persist :transaction {:account-id "acc-b" :type :inflow  :amount 40 :created-at ts}]]
                  effects))))

  (testing "transferring an invalid amount fails, and no money moves"
    (let [repo   (-> {}
                     (step accounts/create-account "acc-a" (tick))
                     (step accounts/create-account "acc-b" (tick))
                     (step accounts/deposit "acc-a" 100 (tick)))
          result (accounts/transfer repo "acc-a" "acc-b" 0 (tick))]
      (is (match? {:error :invalid-amount} result))
      (is (nil? (:repository result)))
      (is (= 100 (:balance (get repo "acc-a"))))))

  (testing "transferring to the same account fails, and no money moves"
    (let [repo   (-> {}
                     (step accounts/create-account "acc-a" (tick))
                     (step accounts/deposit "acc-a" 100 (tick)))
          result (accounts/transfer repo "acc-a" "acc-a" 40 (tick))]
      (is (match? {:error :same-account} result))
      (is (nil? (:repository result)))
      (is (= 100 (:balance (get repo "acc-a"))))))

  (testing "transferring when the source lacks balance fails, and no money moves"
    (let [repo   (-> {}
                     (step accounts/create-account "acc-a" (tick))
                     (step accounts/create-account "acc-b" (tick))
                     (step accounts/deposit "acc-a" 30 (tick)))
          result (accounts/transfer repo "acc-a" "acc-b" 100 (tick))]
      (is (match? {:error :insufficient-balance} result))
      (is (nil? (:repository result)))
      (is (= 30 (:balance (get repo "acc-a"))))
      (is (= 0 (:balance (get repo "acc-b"))))))

  (testing "transferring involving a non-existing account fails, and no money moves"
    (let [repo (-> {}
                   (step accounts/create-account "acc-a" (tick))
                   (step accounts/deposit "acc-a" 100 (tick)))]
      (is (match? {:error :account-not-found}
                  (accounts/transfer repo "acc-a" "ghost" 40 (tick))))
      (is (match? {:error :account-not-found}
                  (accounts/transfer repo "ghost" "acc-a" 40 (tick))))
      (is (= 100 (:balance (get repo "acc-a")))))))
