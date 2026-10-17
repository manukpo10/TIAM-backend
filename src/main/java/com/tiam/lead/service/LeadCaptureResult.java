package com.tiam.lead.service;

/** What {@link LeadService#capture} did with a submission. Only {@link #SAVED} stores anything. */
public enum LeadCaptureResult {
    /** A person: stored. */
    SAVED,
    /** The hidden honeypot field came filled in: a bot that fills every input. */
    HONEYPOT,
    /** Sent faster than a person can fill the form, or without the fill time at all (posted by a script). */
    TOO_FAST,
    /** A Gmail address with dots between almost every letter: the list-bombing pattern (Gmail ignores dots). */
    DOTTED_GMAIL,
    /** Too many submissions from the same IP in the last hour. */
    RATE_LIMITED
}
