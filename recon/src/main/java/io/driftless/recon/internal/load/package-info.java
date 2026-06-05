/**
 * The load generator: drives a realistic, reproducible mix of authorize/capture/reverse against the
 * auth saga in-process and records throughput + latency for the dashboard and fault-injection runs.
 */
package io.driftless.recon.internal.load;
