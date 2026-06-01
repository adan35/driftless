/**
 * Driftless shared kernel: the value types every module agrees on.
 *
 * <p>Contains {@link io.driftless.common.money.Money} (integer minor units, no floating point),
 * strongly-typed identifiers in {@code io.driftless.common.id}, the injectable {@code Clock}
 * convention in {@code io.driftless.common.time}, and the domain-error base in
 * {@code io.driftless.common.error}. This module depends on no other Driftless module; the
 * dependency direction of the whole system points inward to here.
 */
package io.driftless.common;
