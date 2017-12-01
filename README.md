# pg-queue
Database based queue system


# Module specifics

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

