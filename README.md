# clojure-gpiod

[![Clojars Project](https://img.shields.io/clojars/v/com.msladecek/clojure-gpiod.svg)](https://clojars.org/com.msladecek/clojure-gpiod)

Clojure adapter for [`libgpiod`](https://libgpiod.readthedocs.io/en/latest/index.html), a library for controlling GPIO on linux using the GPIO character device.

## Usage

Currently only basic adapters for the [gpiod command line tools](https://libgpiod.readthedocs.io/en/latest/gpio_tools.html#overview) are implemented.
Make sure you have `libgpiod` installed on your system, on rasbpian the library including the command line tools is installed by default.

A few basic examples are available as [babashka tasks](./bb.edn).
Run `bb tasks` to list them out.

The adapters are available in the `com.msladecek.gpio.libgpiod-cli-adapter` namespace.

    ;; Example script that copies signal from pin 3 to pin 17
    ;; 1. Connect an LED between pin 17 and ground.
    ;; 2. Connect a button between pin 3 and ground.
    ;; 3. Run the script, repeatedly press the button.
    ;; The LED should light up whenever the button is pressed

    (require '[com.msladecek.gpio.libgpiod-cli-adapter :as gpio])

    (defn copy-signal [event]
      (gpio/set-lines-once {"GPIO17" (= :falling (:event-type event))}))

    (gpio/monitor-lines copy-signal ["GPIO3"])


The adapters are meant to be compatible with babashka and I tested them only as part of a babashka script running on NixOS on a raspberry pi 400.
However, there is no reason why they shouldn't be usable also from regular JVM clojure.
If you run into any issues with them, feel free to submit a bug report.
