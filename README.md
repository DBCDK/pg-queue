# pg-queue
Database based queue system


# Module specifics

## ee-diags


Application deploy needs this:

```json
    "hazelcast": {
        "clusterName": "${HAZELCAST_CLUSTER}",
        "mapConfig": {
            "known_queues": {
                "ttl": "PT72H",
                "maxIdle": "PT24H",
                "sizePerNode": 1000,
                "format": "BINARY",
                "backupCount": 0,
                "asyncBackupCount":  0,
                "nearCache": {
                    "ttl": "PT24H",
                    "maxIdle": "PT24H",
                    "sizePerNode": 100,
                    "format": "OBJECT"
                }
            }
        }
    }
```
And a headless dns name for the service... and payara network-policies.

Configuration is in: `src/main/resources/pg-queue-endpoint-config.json`

```json
{
    "dataSource": "jdbc/<datasource>",
    "jobLogMapper": "<package>.<class>.<static-instance of JobLogMapper type>",
    "systemName": "<service> - queue admin",
    "diagPercentMatch": <collapsing queue error match ie 70>
}
```


## Throttle

Throttle syntax is a comma separated list of:
 <NUMBER>/[<NUMBER>]TIMEUNIT[!]
  * number of failures for throttle to go into effect
  * in a given period (defaults to 1)
    * defaults to 1
    * takes one of
      * ms
      * s
      * m
      * h
  * should this throttle persist even if a successful result is registered

eq. thread rule, when job starts and job status
  * 1/2s!,5/m
    * 0s fail
    * 2s fail
    * 4s fail
    * 6s fail
    * 8s fail
    * 60s fail
    * 62s fail
  * 1/2s!,5/m
    * 0s fail
    * 2s success
    * 2s fail
    * 4s fail
  * 1/2s!
    * 0s fail
    * 2s success
    * 2s fail
    * 4s fail
  * 1/2s!
    * 0s fail
    * 1s success (other thread)
    * 2s fail
    * 4s fail
  * 1/2s
    * 0s fail
    * 1s success (other thread)
    * 1s fail
    * 3s fail

