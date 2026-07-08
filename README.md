# Simplified Banking System in Clojure

> This is a work in progress for studies purpose. This challenge was not made by AI. The goals is to learn the best way to fit required implementation. Commits may be atomic to handling each step of each development iteration process. The following challenge is inspired by [Banking System Challenge](https://github.com/EricZheng0404/LibreSignal/tree/main/Questions/bank_system).

## About

In this project, I will implement levels 1 through 4 of the proposed challenge. The implementation will be carried out in stages. The goal is to demonstrate my thought process, refactoring, and implementation of features on demand, while also developing my Clojure skills. 

Important to know: no generative AI will be used to support the system design or the narration of my thoughts and strategies. If and when it is used, it will be reported here.

Since the primary goal is to share my process, the implementation time will be much longer than usual, mainly so I can write down my thoughts in stages.

## Level 1

> See requeriments at [Level 1](https://github.com/EricZheng0404/LibreSignal/blob/main/Questions/bank_system/level1.md)

The first thing I do when I receive requirements like this is to extract the use cases in a more objective way. For me, this is similar to chewing the content and simplifying the output. As I do this process, the design begins to take shape in my head.

So I observed the following use cases:

```txt
U01 - Supports to create new accounts
    - Must grant an unique identifier for an account, cannot create if already exists
U02 - Allows to deposit money into accounts
    - Must grant the account exists
U03 - Allows to transferring money between two accounts
    - Must grant both account exists and they should not be the same
    - Source account must have the needed balance
```

Based on these three requirements, I can already understand that we'll be working with two types of data: accounts and transactions. There are several ways to model this. What is certain is that transactions belong to accounts. So I designed the following:

```txt
TransactionOperation
    id: number
    account_id: string
    type: 'inflow' | 'outflow'
    amount: number
    created_at: number

Account
    id: string
    balance: number
    inflow: number
    outflow: number
    transactions: array<TransactionOperation>
    created_at: number
```

As you can see, I've created a list of transactions sorted by type and attached it within the account. I imagine the list of transactions loaded per account will be variable. What I mean is that it will likely be part of a period or the entire period; we don't know. Because of this, I've added caching flags as well: balance, inflows, and outflows, which can carry a broader context.

From this, I can already create an abstract design of how the operations should work. Here, I'm not thinking about the programming language yet, but rather what is expected from each operation.

```txt
accounts/create_account(account_id, timestamp)
    - Make sure account_id is unique
    <= result<boolean> - True if created, False if not
accounts/debit(account_id, amount, timestamp)
    - Make sure account exists
    - Make sure amount is a positive integer
    - Add amount to account transactions and update the balance and outflow caching
    <= result<number | null> - Current account balance or false when cannot debit
accounts/deposit(account_id, amount, timestamp)
    - Make sure account exists
    - Make sure amount is a positive integer
    - Add amount to account transactions and update the balance and inflow caching
    <= result<number | null> - Current account balance or false when cannot deposit
accounts/transfer(source_account_id, target_account_id, amount, timestamp)
    - Source and target accounts must exists
    - Source and target should not be the same
    - Make sure amount is a positive integer
    - Debit from source
    - Deposit at target
    <= result<number | null> - Current source account balance or false when cannot transfer
```

Now, speaking of language and the best possible design for it, this preliminary design makes me think about a few things.

First, I believe that in Clojure it makes sense to think about two key figures: Gary Bernhardt and Rich Hickey. A brief context: in general, both talk about functional programming, and the latter, of course, is the creator of Clojure, and the entire philosophy that underpins the language is supported by him.

That said, I observe in the design I made that there are three layers that need to be designed to better utilize the architecture's implementation: input validation, funcional core, and imperative shell.

1. In the input validation, there is some information that does not pertain to the state of that account, such as whether the "amount" is a positive integer or whether "source" equals "target". This type of validation is simple, fast, and should happen at the edge;
2. In the functional core, we can orchestrate operations that depend on state (whether the account exists, whether it has sufficient balance, etc.) and also the transformation of the values ​​of that account. Nothing is mutated, Clojure already guarantees the principle of immutability, but it's good to highlight it;
3. And finally, in the imperative shell, we take the decision and implement it. It's the perfect moment to trigger the real effects of those operations. I know the challenge doesn't require this level of complexity, but it would be expected in the real world, and it's worth addressing.

Based on these thoughts, I redesigned the strategy for:

```txt
validations/amount
    - Make sure amount is a positive integer
    <= result<boolean>
validations/distinct-accounts(source_account_id, target_account_id)
    - Make sure source and target are not the same
    <= result<boolean>

accounts/create_account(repository, account_id, timestamp)
    - Make sure account_id is unique
    <= result<[repository, account_id, effects] | failure> - New repository with account or failure
    <= effects -> persist<account>, emit<account_created>
accounts/debit(repository, account_id, amount, timestamp)
    - Make sure account exists
    - Make sure amount is valid
    - Make sure balance covers the amount
    - Derive new account: append transaction, recompute balance + outflow
    <= result<[repository, balance, effects] | failure> - New repository and balance, or failure
    <= effects -> persist<transaction>, audit<debit>, emit<debited>
accounts/deposit(repository, account_id, amount, timestamp)
    - Make sure account exists
    - Make sure amount is valid
    - Derive new account: append transaction, recompute balance + inflow
    <= result<[repository, balance, effects] | failure> - New repository and balance, or failure
    <= effects -> persist<transaction>, audit<deposit>, emit<deposited>
accounts/transfer(repository, source_account_id, target_account_id, amount, timestamp)
    - Make sure source and target are distinct
    - Decide debit from source
    - Decide deposit at target
    - (propagate failure if any step fails)
    <= result<[repository, source_balance, effects] | failure> - New repository and source balance
    <= effects -> effects<debit> + effects<deposit> + emit<transfered>

repositories/commit(decision, ...args)
    - Read current repository from the reference
    - Run decision(repository, ...args) -> [repository, value, effects] | failure
    - On failure: return failure, touch nothing
    - On success, in order:
        1. Publish new repository to the reference (atomically)
        2. Run each effect in effects (opaque to commit)
        3. Return value
    <= result<value | failure> - The decision's value, or failure
```

The design above allows for a more efficient separation of responsibilities. Account operations now handle checks and data manipulation, but only reflect the effects of each transformation. Subsequently, when committing to the repository, the effects are consumed and executed properly.

Herein lies the main reason for introducing effects. Although the challenge didn't call for it, I believe it's relevant to consider within the functional and immutable programming ecosystem operating within Clojure. I'm not adding unnecessary complexity in this case, but rather a contract that allows me to make better use of this data. We could easily remove the audit and occurrence events, keeping only the persistence event without damage.

The next natural step for me is to follow a test-driven design strategy. In this case, the operations are quite simple, and we can achieve results quickly with these checks. This will also allow me to validate the API I've already drafted and, if necessary, make changes.

When designing the tests, I usually think first about how to answer what is expected by the use cases; then any additional tests that justify atomicity. That said, here's what I plan to test:

```txt
Based on use cases:

U01 [Create new accounts]
    - creating with a fresh id succeeds
    - the new account starts empty (zero balance, no history)
    - creating with an already-used id fails
    - the failed re-creation leaves the existing account untouched
U02 [Deposit money into accounts]
    - depositing into an existing account increases its balance by the amount
    - the returned balance reflects the new amount
    - depositing into a non-existing account fails
U03 [Transfer money between two accounts]
    - transferring moves the amount from source to target
    - the returned value is the source's new balance
    - the total across both accounts is unchanged by a transfer
    - transferring to the same account fails, and no money moves
    - transferring when the source lacks balance fails, and no money moves
    - transferring involving a non-existing account fails, and no money moves

Atomic tests:

State persistence (through commit)
    - a committed operation is visible to the next one
    - a failed operation leaves the stored state exactly as it was
```

Now, I am ready to starting to code.