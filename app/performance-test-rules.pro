# Only an unused AndroidX Test async screenshot path refers to this optional adapter.
# Performance tests exercise IO, never the async screenshot API.
-dontwarn androidx.concurrent.futures.SuspendToFutureAdapter
