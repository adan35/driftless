/**
 * Spec 09 demo orchestration: a seed-only, non-production driver that exercises the live saga end to
 * end (seed funded accounts → drive a load mix → inject partner faults over the control plane →
 * settle → reconcile) so a reviewer can watch the zero-drift dashboard hold at $0 under faults with a
 * single call. It moves no money of its own beyond a normal, balanced funding post and only walks the
 * ordinary authorize / capture / reverse / fault paths, so it cannot corrupt the invariants.
 */
package io.driftless.recon.internal.demo;
