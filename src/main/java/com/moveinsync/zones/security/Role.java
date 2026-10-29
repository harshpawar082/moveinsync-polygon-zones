package com.moveinsync.zones.security;

/**
 * EMPLOYEE: can read only trips they ride in.
 * OPERATOR: runs trips (start, pings, end), queries zones, reads index status.
 * ADMIN:    everything an operator can, plus creating, editing and deleting zones.
 * Every role is always limited to its own tenant (client company).
 */
public enum Role {
    EMPLOYEE,
    OPERATOR,
    ADMIN
}
