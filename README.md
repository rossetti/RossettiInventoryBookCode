# Kotlin example code

Kotlin implementations of models from *Analysis of Inventory Systems*, built on
the [Kotlin Simulation Library (KSL)](https://github.com/rossetti/KSL).

This is a **standalone Gradle project**. It is published on its own, at
[rossetti/RossettiInventoryBookCode](https://github.com/rossetti/RossettiInventoryBookCode),
and the book is at <https://rossetti.github.io/RossettiInventoryBook/>. The book
refers to the code; the code does not refer to the book, and nothing here needs
the book in order to build.

## Running it

```sh
git clone https://github.com/rossetti/RossettiInventoryBookCode.git
cd RossettiInventoryBookCode
./gradlew run      # runs the default example
./gradlew build    # compiles everything
./gradlew test     # runs the checks that pin the book's printed numbers
```

The first run downloads Gradle, Kotlin, and the KSL from Maven Central, so give
it a minute. After that it is fast.

Requires a JDK 21 or later. The build declares `jvmToolchain(21)` to match the
KSL's own target, so Gradle will provision a matching JDK if the one on your
path is a different version.

## Layout

```
build.gradle.kts       Kotlin JVM + application plugin; KSL from Maven Central
settings.gradle.kts
gradlew, gradle/       the Gradle wrapper (committed, so the build is reproducible)
src/main/kotlin/inventory/
  <topic>/             one package per subject, named for the subject
src/main/resources/
  logback.xml          quiets library logging so example output stands alone
src/test/kotlin/       the checks that pin what the book prints
data/                  the demand histories the fitting sections and exercises use
```

**A package is named for its subject, not for a chapter number.** Chapter numbers
move when the book is revised, and a package named `ch06` then has to be renamed or
it lies. One was, and did: `inventory.ch06` held the newsvendor model, which is
Chapter 7 material. Section labels in the book are semantic for the same reason.

## What is here

| Package | Subject | Chapter |
|---|---|---|
| `inventory.lotsizing` | The economic order quantity and its relatives: production quantity, planned backorders, quantity discounts, sensitivity | Deterministic Lot Sizing |
| `inventory.multiitem` | Constrained and coordinated portfolios: Lagrange multipliers, exchange curves, joint replenishment, power-of-two intervals, serial and distribution networks | Multi-Item and Constrained Systems |
| `inventory.dynamiclotsizing` | Time-varying requirements: the window cost, Wagner-Whitin, the heuristics, the rolling horizon | Dynamic Lot Sizing |
| `inventory.requirementsplanning` | Bills of material, low-level codes, the MRP record, the explosion, distribution networks | Material and Distribution Requirements Planning |
| `inventory.newsvendor` | The single-period model: loss functions, moment matching, distribution fitting, and a Monte Carlo evaluation checked against the critical ratio | Single-Period Stochastic Inventory |
| `inventory.continuousreview` | The $(r, Q)$ policy: lead time demand, the measures, the cost bounds, and the optimization | Continuous and Periodic Review Systems |
| `inventory.periodicreview` | The $(R, S)$ policy, whose protection interval is $R + L$ | Continuous and Periodic Review Systems |
| `inventory.multiechelon` | One-for-one ordering across two levels: Palm's theorem, METRIC, VARI-METRIC, marginal allocation, and the Lagrangian | Multi-Echelon Inventory Systems |

## Changing the KSL version

The dependency is pinned in `build.gradle.kts`:

```kotlin
implementation("io.github.rossetti:KSLCore:R1.7")
```

Bump the version there. The KSL repository's README documents the current
published coordinate.

## License

GPL-3.0. See [LICENSE](LICENSE).
