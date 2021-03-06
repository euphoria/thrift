%%%-------------------------------------------------------------------
%%% File    : thrift_disk_log_transport.erl
%%% Author  : Todd Lipcon <todd@amiestreet.com>
%%% Description : Write-only Thrift transport outputting to disk_log
%%% Created : 22 Apr 2008 by Todd Lipcon <todd@lipcon.org>
%%%
%%% Todo: this might be better off as a gen_server type of transport
%%%       that handles stuff like group commit, similar to TFileTransport
%%%       in cpp land
%%%-------------------------------------------------------------------
-module(thrift_disk_log_transport).

-behaviour(thrift_transport).

%% API
-export([new/2, new_transport_factory/2, new_transport_factory/3]).

%% thrift_transport callbacks
-export([read/2, write/2, force_flush/1, flush/1, close/1]).

%% state
-record(dl_transport, {log,
                       close_on_close = false,
                       sync_every = infinity,
                       sync_tref}).


%% Create a transport attached to an already open log.
%% If you'd like this transport to close the disk_log using disk_log:lclose()
%% when the transport is closed, pass a {close_on_close, true} tuple in the
%% Opts list.
new(LogName, Opts) when is_atom(LogName), is_list(Opts) ->
    State = parse_opts(Opts, #dl_transport{log = LogName}),

    State2 =
        case State#dl_transport.sync_every of
            N when is_integer(N), N > 0 ->
                {ok, TRef} = timer:apply_interval(N, ?MODULE, force_flush, State),
                State#dl_transport{sync_tref = TRef};
            _ -> State
        end,

    thrift_transport:new(?MODULE, State2).


parse_opts([], State) ->
    State;
parse_opts([{close_on_close, Bool} | Rest], State) when is_boolean(Bool) ->
    State#dl_transport{close_on_close = Bool};
parse_opts([{sync_every, Int} | Rest], State) when is_integer(Int), Int > 0 ->
    State#dl_transport{sync_every = Int}.


%%%% TRANSPORT IMPLENTATION %%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%

%% disk_log_transport is write-only
read(_State, Len) ->
    {error, no_read_from_disk_log}.

write(#dl_transport{log = Log}, Data) ->
    disk_log:balog(Log, erlang:iolist_to_binary(Data)).

force_flush(#dl_transport{log = Log}) ->
    error_logger:info_msg("~p syncing~n", [?MODULE]),
    disk_log:sync(Log).

flush(#dl_transport{log = Log, sync_every = SE}) ->
    case SE of
        undefined -> % no time-based sync
            disk_log:sync(Log);
        _Else ->     % sync will happen automagically
            ok
    end.


%% On close, close the underlying log if we're configured to do so.
close(#dl_transport{close_on_close = false}) ->
    ok;
close(#dl_transport{log = Log}) ->
    disk_log:lclose(Log).


%%%% FACTORY GENERATION %%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%

new_transport_factory(Name, ExtraLogOpts) ->
    new_transport_factory(Name, ExtraLogOpts, [{close_on_close, true},
                                               {sync_every, 500}]).

new_transport_factory(Name, ExtraLogOpts, TransportOpts) ->
    F = fun() -> factory_impl(Name, ExtraLogOpts, TransportOpts) end,
    {ok, F}.

factory_impl(Name, ExtraLogOpts, TransportOpts) ->
    LogOpts = [{name, Name},
               {format, external},
               {type, wrap} |
               ExtraLogOpts],
    Log =
        case disk_log:open(LogOpts) of
            {ok, Log} ->
                Log;
            {repaired, Log, Info1, Info2} ->
                error_logger:info_msg("Disk log ~p repaired: ~p, ~p~n", [Log, Info1, Info2]),
                Log
        end,
    new(Log, TransportOpts).
