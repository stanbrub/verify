package io.deephaven.verify.util;

import java.util.*;

public class Metrics {
	final Map<String,Number> metrics = new TreeMap<>();
	final long timestamp = System.currentTimeMillis();
	final String name;
	final String category;
	
	public Metrics(String name, String category) {
		this.name = name;
		this.category = category;
	}
	
	public Metrics set(String name, Number value) {
		metrics.put(name, value);
		return this;
	}
	
	public Number get(String name, Number value) {
		return metrics.get(name);
	}
	
	public long timestamp() {
		return timestamp;
	}
	
	public Set<String> getNames() {
		return Collections.unmodifiableSet(metrics.keySet());
	}

	@Override
	public String toString() {
		Map<String,Object> m = new TreeMap<>(metrics);
		m.put("name", name);
		m.put("category", category);
		m.put("timestamp", timestamp);
		return m.toString();
	}
	
}
