import json
import os
from apimgt import swagger

try:
  from unittest import TestCase
except ImportError:
  from unittest.case import TestCase

class TestSwagger(TestCase):

  def load_file(self, file_name):
    current_dir = os.path.dirname(os.path.abspath(__file__))
    full_path = os.path.join(current_dir, 'samples', file_name)
    file_handle = open(full_path, 'r')
    spec = json.load(file_handle)
    file_handle.close()
    return spec

  def test_resource_path_incompatibility(self):
    api1 = self.load_file('1.json')
    api2 = self.load_file('2.json')
    status, message = swagger.is_api_compatible(api1, api2)
    self.assertFalse(status)

  def test_base_path_incompatibility(self):
    api1 = self.load_file('1.json')
    api2 = self.load_file('3.json')
    status, message = swagger.is_api_compatible(api1, api2)
    self.assertFalse(status)

  def test_missing_op_incompatibility(self):
    api1 = self.load_file('1.json')
    api2 = self.load_file('4.json')
    status, message = swagger.is_api_compatible(api1, api2)
    self.assertFalse(status)

  def test_method_compatibility(self):
    api1 = self.load_file('1.json')
    api2 = self.load_file('6.json')
    status, message = swagger.is_api_compatible(api1, api2)
    self.assertTrue(status)

  def test_http_method_incompatibility(self):
    api1 = self.load_file('1.json')
    api2 = self.load_file('5.json')
    status, message = swagger.is_api_compatible(api1, api2)
    self.assertFalse(status)

