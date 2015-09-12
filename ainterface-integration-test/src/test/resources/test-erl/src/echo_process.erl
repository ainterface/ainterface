-module(echo_process).

%% API
-export([start_link/0, init/0]).

start_link() ->
  register(echo, Pid=spawn_link(?MODULE, init, [])),
  {ok, Pid}.

init() ->
  loop().

loop() ->
  receive
    {Pid, Msg} ->
      Pid ! {self(), Msg},
      loop()
  end.
