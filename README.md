# Cinderella Crate

[Pallet](http://palletops.com) crate for installing and configuring cinderella.

## Install

Add the cinderella crate to your `:dependencies`:

    [org.cloudhoist/cinderella "0.1.0-SNAPSHOT"]

## Building

You will need to use maven's `settings.xml` to specify repositories. You can do
this using [pallet-settings-xml](https://github.com/pallet/pallet-settings-xml).

## Live test

To run a live test,

    mvn test -Plive-test -Dpallet.test.service-name=xxx

where the service-name `xxx` should be replaced with your service name used in
pallet's config.clj or services config.

## License

Licensed under the
[Apache License](http://www.apache.org/licenses/LICENSE-2.0.html), Version 2.0.
