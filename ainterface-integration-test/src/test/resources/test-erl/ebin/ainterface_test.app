{application, ainterface_test, [
  {description, "The application for integration tests of Ainterface."},
  {vsn, "1"},
  {modules, [ainterface_supervisor, aitnerface_test, echo_process]},
  {registered, [echo]},
  {applications, [
    kernel,
    stdlib
  ]},
  {mod, {ainterface_test, []}},
  {env, []}
]}.