# JUXT Accounting

Free (as in freedom) double-entry book-keeping software.

## Introduction

For many, keeping accounts is an unavoidable chore. There are a lot of software packages available that can help, but the majority of these are proprietary software and don't make it possible for their users to inspect or adapt the software for their own purposes.

Asking for a single accounting package to do everything is asking for a hugely-bloated complex software program. Better to have something small that covers the basics but can be extended to fit a particular need.

> "Given enough eyeballs, all bugs are shallow." -- Linus' Law

Accounting mistakes in software are more likely if only a few people are free to inspect the code.

Finally, the HMRC (the UK tax authority) force businesses into using proprietary software by not allowing any free-software alternatives. In the case of payroll software it offers a single choice of proprietary software for download. This seems to be at odds with the official UK government's advice to its own departments :-

> "Software that is developed to meet the needs of the government - whether it’s developed by government employees, contractors or by a supplier - should be shared wherever possible under a permissive, GPL-compatible open source licence (eg MIT/X11 or 3-clause BSD) so that it can be widely used and improved." -- https://www.gov.uk/service-manual/making-software/choosing-technology.html

## Usage

Ensure you have Java and [Leiningen](http://leiningen.org) installed.

To start the system :-

    $ lein repl

## Examples

A example of using the API can be found in the file ```test/juxt/accounting/example.clj```.

## Scope

I wrote _JUXT Accounting_ to manage our JUXT business affairs so it's naturally tailored to these requirements. However, double-entry bookkeeping, [invented in Italy in the 15th century](https://en.wikipedia.org/wiki/Double-entry_bookkeeping_system), is used worldwide today.

## Principles

* Free software - designed to give maximum flexibility to _users_ (at the expense of software providers)
* Strict separation of code and data, that is, separation between this software and your accounts data.

## Technology

_JUXT Accounting_ is written using the very latest/best software development database technologies (Clojure and Datomic if you want to know). This gives the application plenty of flexibility without incurring the usual code bloat and bugs. It uses __BigDecimal__s and __Joda Money__ - so no floats, doubles and head-scratching rounding errors. It's money after all (but please note the disclaimer below).

At the very least, JUXT Accounting provides an example of a Datomic-based application which you are free to copy and learn from.

## Development

You are free to make your own modifications to the software for any purpose. To help ensure these modifications don't break existing functionality, you can run the test suite (but it's not obligatory).

    $ lein test

## Features

JUXT Accounting is being built from the ground up. There's not much of a user interface right now. I'm mostly focussed on the back-end. But there's support for adding transaction entries, issuing and printing invoices, and even reports (if you're willing to learn [Datomic's rich query syntax](http://docs.datomic.com/query.html) and write them yourself!)

## Accounting terminology

Accountancy, not unlike any other profession, has its own jargon. Debits are where someone owes you money. Credits are where you owe them money. So debits are good and credits a bad.

This might be the direct opposite of what you understand debits and credits to mean, so you may have to spend some time reprogramming your brain. Confused? This example helped me: when you receive a bank statement which shows you are in debit, this is _good_. _Good for the bank, that is_. After all, it's _their_ statement about _you_, not _your_ statement about _them_). _Debits are good, credits are bad_. Credits are _bad_ (gosh, I owe somebody some money), debits are _good_ (great, finally someone owes _me_ money!).

The double-entry bookkeeping approach is about _balance_. It demands that whenever you record a purchase, sale, expense or some other category of income or expenditure, you record _two_ entries - one is a debit, the other a credit _of the same amount_. The idea is that if every transaction you enter is balanced, your books will balance.

## API usage

### Adding transactions

A transaction is a set of entries in ledgers which all relate to a particular purchase, sale or generic movement of money that needs to be accounted for. In double-entry bookkeeping, we would normally have 2 entries per transaction, one for the debit and one for the credit. But I've generalized this concept, because you often have tax and other charges that are incurred with the transaction, so the API lets you assemble all these entries together. But if the total of the debits don't equate to the total of the credits, you get an exception thrown and the books are protected from being unbalanced from your transaction. This is the sort of thing you want the API to do for you.

Note: This check does not happen for multi-currency transactions.

These entries are first _assembled_ into a list before being inserted, all at once, into the database. This list of entries will contain debits and credits.

## Pricing

If you make use of the software and it becomes valuable to you, we ask that you make a one-off payment of £100 or BTC 1.00. These payments help to improve the software. Please make UK cheques payable to JUXT LTD. and send to :-

    JUXT LTD.
    8 Barbers Walk,
    Tring.
    HP23 4DB
    UK

We welcome payment in BTC. Please contact ```info@juxt.pro``` and ask for our BTC payment address.

## License

This software is licensed under the Affero General Public License 3.0. This license does not allow you to use this software as the basis for a proprietary software package or cloud-hosted service, so please don't do that. There are too many proprietary accounting software packages already, let's not create any more.

See the LICENSE file for full details.

Please note that there are required system dependencies that are not included (Java, Leiningen, Clojure, Datomic and other libraries) which are licensed under different terms.

## Copyright

Copyright © 2013 JUXT LTD.
