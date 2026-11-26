package com.zigzaego.api.job.entity;

/** job 상태. QUEUED → RUNNING → DONE | FAILED. V1 {@code job.status} CHECK 와 같다. */
public enum JobStatus {
	QUEUED,
	RUNNING,
	DONE,
	FAILED
}
