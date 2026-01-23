#!/usr/bin/env python3
"""
Generate enhanced Grafana dashboard with bottleneck detection panels
"""
import json

def create_dashboard():
    # Base dashboard structure
    dashboard = {
        "uid": "ratelimit-benchmark",
        "title": "Redis Rate Limit Benchmark (Enhanced v2)",
        "tags": ["benchmark", "redis", "grpc", "envoy", "bottleneck"],
        "timezone": "browser",
        "schemaVersion": 38,
        "version": 4,
        "refresh": "5s",
        "panels": []
    }
    
    y_pos = 0
    
    # Row 1: Overview
    dashboard["panels"].append({
        "type": "row",
        "title": "📊 Overview - Throughput & Latency & Errors",
        "collapsed": False,
        "gridPos": {"h": 1, "w": 24, "x": 0, "y": y_pos}
    })
    y_pos += 1
    
    dashboard["panels"].extend([
        create_timeseries("Total Throughput (RPS)", "reqps",
            [
                ("sum(irate(ratelimit_requests_total[1m]))", "App RPS"),
                ("sum(irate(envoy_http_grpc_downstream_rq_total[1m]))", "Envoy RPS")
            ],
            {"h": 8, "w": 8, "x": 0, "y": y_pos}
        ),
        create_timeseries("End-to-End Latency", "ms",
            [
                ("histogram_quantile(0.50, sum(rate(envoy_http_grpc_downstream_rq_time_bucket[1m])) by (le))", "P50"),
                ("histogram_quantile(0.95, sum(rate(envoy_http_grpc_downstream_rq_time_bucket[1m])) by (le))", "P95"),
                ("histogram_quantile(0.99, sum(rate(envoy_http_grpc_downstream_rq_time_bucket[1m])) by (le))", "P99")
            ],
            {"h": 8, "w": 8, "x": 8, "y": y_pos}
        ),
        create_timeseries("Error Rate (%)", "percent",
            [
                ("sum(irate(ratelimit_redis_errors_total[1m])) / sum(irate(ratelimit_requests_total[1m])) * 100", "Redis Errors %")
            ],
            {"h": 8, "w": 8, "x": 16, "y": y_pos},
            thresholds=[("green", None), ("yellow", 1), ("red", 5)]
        )
    ])
    y_pos += 8
    
    # Row 2: Container Resource Usage (NEW - for bottleneck detection)
    dashboard["panels"].append({
        "type": "row",
        "title": "🔥 Container Resources (Bottleneck Detection)",
        "collapsed": False,
        "gridPos": {"h": 1, "w": 24, "x": 0, "y": y_pos}
    })
    y_pos += 1
    
    dashboard["panels"].extend([
        create_timeseries("Container CPU Usage (%)", "percent",
            [
                ("rate(container_cpu_usage_seconds_total{name=~\"ratelimit-app-.*\"}[1m]) * 100", "{{name}}"),
                ("rate(container_cpu_usage_seconds_total{name=\"redis-benchmark\"}[1m]) * 100", "Redis")
            ],
            {"h": 8, "w": 8, "x": 0, "y": y_pos},
            thresholds=[("green", None), ("yellow", 70), ("red", 90)]
        ),
        create_timeseries("Container Memory", "bytes",
            [
                ("container_memory_usage_bytes{name=~\"ratelimit-app-.*\"}", "{{name}}"),
                ("container_memory_usage_bytes{name=\"redis-benchmark\"}", "Redis")
            ],
            {"h": 8, "w": 8, "x": 8, "y": y_pos}
        ),
        create_timeseries("Latency Breakdown (Overhead)", "ms",
            [
                ("histogram_quantile(0.99, sum(rate(envoy_http_grpc_downstream_rq_time_bucket[1m])) by (le))", "Total P99"),
                ("histogram_quantile(0.99, sum(rate(ratelimit_redis_latency_seconds_bucket[1m])) by (le)) * 1000", "Redis P99"),
                ("histogram_quantile(0.99, sum(rate(envoy_http_grpc_downstream_rq_time_bucket[1m])) by (le)) - histogram_quantile(0.99, sum(rate(ratelimit_redis_latency_seconds_bucket[1m])) by (le)) * 1000", "App Overhead P99")
            ],
            {"h": 8, "w": 8, "x": 16, "y": y_pos}
        )
    ])
    y_pos += 8
    
    # Row 3: JVM & GC Metrics (NEW)
    dashboard["panels"].append({
        "type": "row",
        "title": "☕ JVM & GC Pressure",
        "collapsed": False,
        "gridPos": {"h": 1, "w": 24, "x": 0, "y": y_pos}
    })
    y_pos += 1
    
    dashboard["panels"].extend([
        create_timeseries("GC Pressure (%)", "percent",
            [
                ("sum(rate(jvm_gc_pause_seconds_sum[1m])) by (instance) * 100", "GC % - {{instance}}")
            ],
            {"h": 8, "w": 8, "x": 0, "y": y_pos},
            thresholds=[("green", None), ("yellow", 5), ("red", 10)]
        ),
        create_timeseries("JVM Heap Memory", "bytes",
            [
                ("sum(jvm_memory_used_bytes{area=\"heap\"}) by (instance)", "Used - {{instance}}"),
                ("sum(jvm_memory_max_bytes{area=\"heap\"}) by (instance)", "Max - {{instance}}")
            ],
            {"h": 8, "w": 8, "x": 8, "y": y_pos}
        ),
        create_timeseries("Thread Count", "short",
            [
                ("app_thread_active", "Active - {{instance}}"),
                ("jvm_threads_live_threads", "JVM Live - {{instance}}")
            ],
            {"h": 8, "w": 8, "x": 16, "y": y_pos}
        )
    ])
    y_pos += 8
    
    # Row 4: Redis Performance
    dashboard["panels"].append({
        "type": "row",
        "title": "🔴 Redis Performance",
        "collapsed": False,
        "gridPos": {"h": 1, "w": 24, "x": 0, "y": y_pos}
    })
    y_pos += 1
    
    dashboard["panels"].extend([
        create_timeseries("Redis Latency (App Side)", "ms",
            [
                ("irate(ratelimit_redis_latency_seconds_sum[1m]) / irate(ratelimit_redis_latency_seconds_count[1m]) * 1000", "Avg"),
                ("histogram_quantile(0.95, sum(rate(ratelimit_redis_latency_seconds_bucket[1m])) by (le)) * 1000", "P95"),
                ("histogram_quantile(0.99, sum(rate(ratelimit_redis_latency_seconds_bucket[1m])) by (le)) * 1000", "P99")
            ],
            {"h": 8, "w": 8, "x": 0, "y": y_pos}
        ),
        create_timeseries("Redis Ops/sec", "ops",
            [
                ("irate(redis_commands_processed_total[1m])", "Commands/sec")
            ],
            {"h": 8, "w": 8, "x": 8, "y": y_pos}
        ),
        create_timeseries("Redis CPU", "short",
            [
                ("rate(redis_cpu_sys_seconds_total[1m]) + rate(redis_cpu_user_seconds_total[1m])", "Total CPU Cores")
            ],
            {"h": 8, "w": 8, "x": 16, "y": y_pos}
        )
    ])
    y_pos += 8
    
    # Row 5: Redis Resources
    dashboard["panels"].append({
        "type": "row",
        "title": "🔴 Redis Resources",
        "collapsed": False,
        "gridPos": {"h": 1, "w": 24, "x": 0, "y": y_pos}
    })
    y_pos += 1
    
    dashboard["panels"].extend([
        create_timeseries("Redis Memory", "bytes",
            [
                ("redis_memory_used_bytes", "Used"),
                ("redis_memory_max_bytes", "Max"),
                ("redis_memory_used_rss_bytes", "RSS")
            ],
            {"h": 8, "w": 12, "x": 0, "y": y_pos}
        ),
        create_timeseries("Redis Connections & Keys", "short",
            [
                ("redis_connected_clients", "Clients"),
                ("redis_db_keys{db=\"db0\"}", "Keys")
            ],
            {"h": 8, "w": 12, "x": 12, "y": y_pos}
        )
    ])
    y_pos += 8
    
    # Row 6: Envoy
    dashboard["panels"].append({
        "type": "row",
        "title": "🌐 Envoy Load Balancer",
        "collapsed": False,
        "gridPos": {"h": 1, "w": 24, "x": 0, "y": y_pos}
    })
    y_pos += 1
    
    dashboard["panels"].extend([
        create_timeseries("Envoy RPS by Status", "reqps",
            [
                ("sum(irate(envoy_http_grpc_downstream_rq_2xx[1m]))", "2xx"),
                ("sum(irate(envoy_http_grpc_downstream_rq_4xx[1m]))", "4xx"),
                ("sum(irate(envoy_http_grpc_downstream_rq_5xx[1m]))", "5xx")
            ],
            {"h": 8, "w": 8, "x": 0, "y": y_pos}
        ),
        create_timeseries("Envoy Traffic: Downstream vs Upstream", "reqps",
            [
                ("sum(irate(envoy_http_grpc_downstream_rq_total[1m]))", "Downstream (Total)"),
                ("sum(irate(envoy_cluster_upstream_rq_total{envoy_cluster_name=\"grpc_backend\"}[1m]))", "Upstream (Total)")
            ],
            {"h": 8, "w": 8, "x": 8, "y": y_pos}
        ),
        create_timeseries("Envoy Connections", "short",
            [
                ("envoy_cluster_upstream_cx_active{envoy_cluster_name=\"grpc_backend\"}", "Active Conns")
            ],
            {"h": 8, "w": 8, "x": 16, "y": y_pos}
        )
    ])
    y_pos += 8
    
    # Row 7: Stats
    dashboard["panels"].append({
        "type": "row",
        "title": "📈 Quick Stats",
        "collapsed": False,
        "gridPos": {"h": 1, "w": 24, "x": 0, "y": y_pos}
    })
    y_pos += 1
    
    dashboard["panels"].extend([
        create_stat("RPS", "reqps", "sum(irate(ratelimit_requests_total[1m]))", 
                    {"h": 4, "w": 6, "x": 0, "y": y_pos},
                    [("red", None), ("yellow", 1000), ("green", 5000)]),
        create_stat("P99 Latency", "ms", "histogram_quantile(0.99, sum(rate(envoy_http_grpc_downstream_rq_time_bucket[1m])) by (le))",
                    {"h": 4, "w": 6, "x": 6, "y": y_pos},
                    [("green", None), ("yellow", 20), ("red", 100)]),
        create_stat("Error %", "percent", "sum(irate(ratelimit_redis_errors_total[1m])) / sum(irate(ratelimit_requests_total[1m])) * 100 or vector(0)",
                    {"h": 4, "w": 6, "x": 12, "y": y_pos},
                    [("green", None), ("yellow", 1), ("red", 5)]),
        create_stat("GC %", "percent", "sum(rate(jvm_gc_pause_seconds_sum[1m])) * 100",
                    {"h": 4, "w": 6, "x": 18, "y": y_pos},
                    [("green", None), ("yellow", 5), ("red", 10)])
    ])
    
    return dashboard

def create_timeseries(title, unit, targets, grid_pos, thresholds=None):
    panel = {
        "type": "timeseries",
        "title": title,
        "datasource": "Prometheus",
        "fieldConfig": {
            "defaults": {
                "unit": unit,
                "custom": {
                    "lineWidth": 2,
                    "fillOpacity": 10
                }
            }
        },
        "targets": [{"expr": expr, "legendFormat": legend} for expr, legend in targets],
        "gridPos": grid_pos
    }
    if thresholds:
        panel["fieldConfig"]["defaults"]["thresholds"] = {
            "mode": "absolute",
            "steps": [{"color": color, "value": value} for color, value in thresholds]
        }
    return panel

def create_stat(title, unit, expr, grid_pos, thresholds):
    return {
        "type": "stat",
        "title": title,
        "datasource": "Prometheus",
        "fieldConfig": {
            "defaults": {
                "unit": unit,
                "thresholds": {
                    "mode": "absolute",
                    "steps": [{"color": color, "value": value} for color, value in thresholds]
                }
            }
        },
        "options": {
            "colorMode": "value",
            "graphMode": "area"
        },
        "targets": [{"expr": expr}],
        "gridPos": grid_pos
    }

if __name__ == "__main__":
    dashboard = create_dashboard()
    print(json.dumps(dashboard, indent=2))
