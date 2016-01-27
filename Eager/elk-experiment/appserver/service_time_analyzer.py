import argparse
import httplib
import json
import numpy
import re
import time

class RequestInfo:
    def __init__(self, req, filtered=[]):
        self.key = req['key']
        self.timestamp = req['request_timestamp']['value_as_string']
        self.api_calls = req['doc_count']
        self.service_times = {}
        services = req['group_by_service']['buckets']
        for service in services:
            name = service['key']
            if name in filtered:
                continue
            value = service['service_time']['value']
            self.service_times[name] = value
        self.total_time = sum(self.service_times.values())

def get_digit(delta_str):
    return int(delta_str[:len(delta_str)-1])
        
def parse_time_delta(delta_str):
    pattern = re.compile('^(\d+[dhms]\s*)+$')
    if pattern.match(delta_str):
        segments = re.split('(\d+[dhms]\s*)', delta_str)
        segments = map(lambda s: s.strip(), filter(lambda s: len(s) > 0, segments))
        result = 0
        for segment in segments:
            if segment.endswith('s'):
                result += get_digit(segment) * 1000
            elif segment.endswith('m'):
                result += get_digit(segment) * 1000 * 60
            elif segment.endswith('h'):
                result += get_digit(segment) * 1000 * 60 * 60
            elif segment.endswith('d'):
                result += get_digit(segment) * 1000 * 60 * 60 * 24
            else:
                raise ValueError('Invalid time delta string ' + segment)
        return result
    else:
        raise ValueError('Invalid time delta string ' + delta_str)
        
def get_request_info(server, port, index, app, time_window, filtered):
    start_time = long(time.time() * 1000) - time_window
    filtered_query = {
      'filtered' : {
         'query' : { 'term' : { 'appId' : app }},
         'filter' : { 'range' : { 'timestamp' : { 'gte' : start_time}}}
       }
    }
    query = {
      'query' : filtered_query,
      'sort': { 'timestamp' : { 'order' : 'asc'}},
      'aggs': {
        'group_by_request' : {
          'terms' : { 'field' : 'requestId.raw', 'size': 0, 'order': {'request_timestamp': 'asc'} },
          'aggs': {
            'request_timestamp': {
              'min': { 'field': 'timestamp'}
            },
            'group_by_service': {
              'terms': { 'field': 'service.raw' },
              'aggs': {
                'service_time': {
                  'sum': { 'field': 'elapsed' }
                }
              }
            }
          }
        }
      }
    }

    path = '/{0}/apicall/_search'.format(index)
    conn = httplib.HTTPConnection(server, port)
    conn.request('POST', path, json.dumps(query))
    response = conn.getresponse()
    data = response.read()
    conn.close()
    if response.status != 200:
        error_message = 'Server returned unexpected status: {0}\n{1}'.format(response.status, data)
        raise RuntimeError(error_message)
    output = json.loads(data)
    requests = output['aggregations']['group_by_request']['buckets']
    result = []
    for req in requests:
        result.append(RequestInfo(req, filtered))
    return result

def calculate_summary(requests, func):
    values = filter(lambda val: val > 0, map(func, requests))
    if values:
        return numpy.mean(values), numpy.std(values), numpy.median(values), len(values)
    else:
        return 0, 0, 0, 0

def print_output(requests, order):
    service_names = [ 'datastore_v3', 'memcache', 'urlfetch' ]
    print 'requestId  datastore_v3 (datastore_v3%)  memcache (memcache%)  urlfetch (urlfetch%)  total_time api_calls'
    sorted_requests = requests
    if order:
        sorted_requests = sorted(requests, key=lambda x: x.total_time)
    for req in sorted_requests:
        record = '{0}  {1}  '.format(req.timestamp, req.key)
        for k in service_names:
            value = req.service_times.get(k, 0.0)
            record += '{0}  ({1:.2f})  '.format(value, (value/req.total_time) * 100.0)
        record += '{0}  {1}'.format(req.total_time, req.api_calls)
        print record
    print
    print 'Total requests: {0}'.format(len(requests))
    print '[service] Name mean std median count'
    print '[service] Datastore {0:.2f} {1:.2f} {2:.2f} {3}'.format(
        *calculate_summary(requests, lambda req: req.service_times.get('datastore_v3', -1)))
    print '[service] Memcache {0:.2f} {1:.2f} {2:.2f} {3}'.format(
        *calculate_summary(requests, lambda req: req.service_times.get('memcache', -1)))
    print '[service] URLFetch {0:.2f} {1:.2f} {2:.2f} {3}'.format(
        *calculate_summary(requests, lambda req: req.service_times.get('urlfetch', -1)))
    print '[service] TotalTime {0:.2f} {1:.2f} {2:.2f} {3}'.format(
        *calculate_summary(requests, lambda req: req.total_time))

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Analyzes execution time of cloud services.')
    parser.add_argument('--server', '-s', dest='server', default='128.111.179.159')
    parser.add_argument('--port', '-p', type=int, dest='port', default=9200)
    parser.add_argument('--index', '-i', dest='index', default='appscale-internal')
    parser.add_argument('--app', '-a', dest='app', default='watchtower')
    parser.add_argument('--time_window', '-t', dest='time_window', default='1h')
    parser.add_argument('--order', '-o', dest='order', action='store_true')
    parser.add_argument('--filtered_services', '-fs', nargs='+', dest='filtered_services', default=[])
    args = parser.parse_args()
    time_window_ms = parse_time_delta(args.time_window)
    requests = get_request_info(args.server, args.port, args.index, args.app, time_window_ms, args.filtered_services)
    if requests:
        print_output(requests, args.order)
    else:
        print 'No request information found'