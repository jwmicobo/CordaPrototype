Client RPC
==========

There are multiple ways to interact with a node from a *client program*, but if your client is written in a JVM
compatible language the easiest way to do so is using the client library. The library connects to your running
node using a message queue protocol and then provides a simple RPC interface to interact with it. You make calls
on a Java object as normal, and the marshalling back and forth is handled for you.

The starting point for the client library is the `CordaRPCClient`_ class. This provides a ``start`` method that
returns a `CordaRPCConnection`_, holding an implementation of the `CordaRPCOps`_ that may be accessed with ``proxy``
in Kotlin and ``getProxy()`` in Java. Observables that are returned by RPCs can be subscribed to in order to receive
an ongoing stream of updates from the node. More detail on how to use this is provided in the docs for the proxy method.

.. warning:: The returned `CordaRPCConnection`_ is somewhat expensive to create and consumes a small amount of
   server side resources. When you're done with it, call ``close`` on it. Alternatively you may use the ``use``
   method on `CordaRPCClient`_ which cleans up automatically after the passed in lambda finishes. Don't create
   a new proxy for every call you make - reuse an existing one.

For a brief tutorial on how one can use the RPC API see :doc:`tutorial-clientrpc-api`.

RPC permissions
---------------
If a node's owner needs to interact with their node via RPC (e.g. to read the contents of the node's storage), they
must define one or more RPC users. Each user is authenticated with a username and password, and is assigned a set of
permissions that RPC can use for fine-grain access control.

These users are added to the node's ``node.conf`` file.

The simplest way of adding an RPC user is to include it in the ``rpcUsers`` list:

.. container:: codeset

    .. sourcecode:: groovy

        rpcUsers=[
            {
                username=exampleUser
                password=examplePass
                permissions=[]
            }
            ...
        ]

Users need permissions to invoke any RPC call. By default, nothing is allowed. These permissions are specified as follows:

.. container:: codeset

    .. sourcecode:: groovy

        rpcUsers=[
            {
                username=exampleUser
                password=examplePass
                permissions=[
                    "StartFlow.net.corda.flows.ExampleFlow1",
                    "StartFlow.net.corda.flows.ExampleFlow2"
                ]
            }
            ...
        ]

Permissions Syntax
^^^^^^^^^^^^^^^^^^

Fine grained permissions allow a user to invoke a specific RPC operation, or to start a specific flow. The syntax is:

- to start a specific flow: ``StartFlow.<fully qualified flow name>`` e.g., ``StartFlow.net.corda.flows.ExampleFlow1``.
- to invoke a RPC operation: ``InvokeRpc.<rpc method name>`` e.g., ``InvokeRpc.nodeInfo``.
.. note:: Permission ``InvokeRpc.startFlow`` allows a user to initiate all flows.

RPC security management
-----------------------

Setting ``rpcUsers`` provides a simple way of granting RPC permissions to a fixed set of users, but has some
obvious shortcomings. To support use cases aiming for higher security and flexibility, Corda offers additional security
features such as:

 * Fetching users credentials and permissions from an external data source (e.g.: a remote RDBMS), with optional in-memory
   caching. In particular, this allows credentials and permissions to be updated externally without requiring nodes to be
   restarted.
 * Password stored in hash-encrypted form. This is regarded as must-have when security is a concern. Corda currently supports
   a flexible password hash format conforming to the Modular Crypt Format provided by the `Apache Shiro framework <https://shiro.apache.org/static/1.2.5/apidocs/org/apache/shiro/crypto/hash/format/Shiro1CryptFormat.html>`_

These features are controlled by a set of options nested in the ``security`` field of ``node.conf``.
The following example shows how to configure retrieval of users credentials and permissions from a remote database with
passwords in hash-encrypted format and enable in-memory caching of users data:

.. container:: codeset

    .. sourcecode:: groovy

        security = {
            authService = {
                dataSource = {
                    type = "DB",
                    passwordEncryption = "SHIRO_1_CRYPT",
                    connection = {
                       jdbcUrl = "<jdbc connection string>"
                       username = "<db username>"
                       password = "<db user password>"
                       driverClassName = "<JDBC driver>"
                    }
                }
                options = {
                     cache = {
                        expireAfterSecs = 120
                        maxEntries = 10000
                     }
                }
            }
        }

It is also possible to have a static list of users embedded in the ``security`` structure by specifying a ``dataSource``
of ``INMEMORY`` type:

.. container:: codeset

    .. sourcecode:: groovy

        security = {
            authService = {
                dataSource = {
                    type = "INMEMORY",
                    users = [
                        {
                            username = "<username>",
                            password = "<password>",
                            permissions = ["<permission 1>", "<permission 2>", ...]
                        },
                        ...
                    ]
                }
            }
        }

.. warning:: A valid configuration cannot specify both the ``rpcUsers`` and ``security`` fields. Doing so will trigger
   an exception at node startup.

Authentication/authorisation data
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The ``dataSource`` structure defines the data provider supplying credentials and permissions for users. There exist two
supported types of such data source, identified by the ``dataSource.type`` field:

 :INMEMORY: A static list of user credentials and permissions specified by the ``users`` field.

 :DB: An external RDBMS accessed via the JDBC connection described by ``connection``. Note that, unlike the ``INMEMORY``
  case, in a user database permissions are assigned to *roles* rather than individual users. The current implementation
  expects the database to store data according to the following schema:

       - Table ``users`` containing columns ``username`` and ``password``. The ``username`` column *must have unique values*.
       - Table ``user_roles`` containing columns ``username`` and ``role_name`` associating a user to a set of *roles*.
       - Table ``roles_permissions`` containing columns ``role_name`` and ``permission`` associating a role to a set of
         permission strings.

  .. note:: There is no prescription on the SQL type of each column (although our tests were conducted on ``username`` and
    ``role_name`` declared of SQL type ``VARCHAR`` and ``password`` of ``TEXT`` type). It is also possible to have extra columns
    in each table alongside the expected ones.

Password encryption
^^^^^^^^^^^^^^^^^^^

Storing passwords in plain text is discouraged in applications where security is critical. Passwords are assumed
to be in plain format by default, unless a different format is specified by the ``passwordEncryption`` field, like:

.. container:: codeset

    .. sourcecode:: groovy

        passwordEncryption = SHIRO_1_CRYPT

``SHIRO_1_CRYPT`` identifies the `Apache Shiro fully reversible
Modular Crypt Format <https://shiro.apache.org/static/1.2.5/apidocs/org/apache/shiro/crypto/hash/format/Shiro1CryptFormat.html>`_,
it is currently the only non-plain password hash-encryption format supported. Hash-encrypted passwords in this
format can be produced by using the `Apache Shiro Hasher command line tool <https://shiro.apache.org/command-line-hasher.html>`_.

Caching user accounts data
^^^^^^^^^^^^^^^^^^^^^^^^^^

A cache layer on top of the external data source of users credentials and permissions can significantly improve
performances in some cases, with the disadvantage of causing a (controllable) delay in picking up updates to the underlying data.
Caching is disabled by default, it can be enabled by defining the ``options.cache`` field in ``security.authService``,
for example:

.. container:: codeset

    .. sourcecode:: groovy

        options = {
             cache = {
                expireAfterSecs = 120
                maxEntries = 10000
             }
        }

This will enable a non-persistent cache contained in the node's memory with maximum number of entries set to ``maxEntries``
where entries are expired and refreshed after ``expireAfterSecs`` seconds.

Observables
-----------
The RPC system handles observables in a special way. When a method returns an observable, whether directly or
as a sub-object of the response object graph, an observable is created on the client to match the one on the
server. Objects emitted by the server-side observable are pushed onto a queue which is then drained by the client.
The returned observable may even emit object graphs with even more observables in them, and it all works as you
would expect.

This feature comes with a cost: the server must queue up objects emitted by the server-side observable until you
download them. Note that the server side observation buffer is bounded, once it fills up the client is considered
slow and kicked. You are expected to subscribe to all the observables returned, otherwise client-side memory starts
filling up as observations come in. If you don't want an observable then subscribe then unsubscribe immediately to
clear the client-side buffers and to stop the server from streaming. If your app quits then server side resources
will be freed automatically.

.. warning:: If you leak an observable on the client side and it gets garbage collected, you will get a warning
   printed to the logs and the observable will be unsubscribed for you. But don't rely on this, as garbage collection
   is non-deterministic.

Futures
-------
A method can also return a ``ListenableFuture`` in its object graph and it will be treated in a similar manner to
observables. Calling the ``cancel`` method on the future will unsubscribe it from any future value and release any resources.

Versioning
----------
The client RPC protocol is versioned using the node's Platform Version (see :doc:`versioning`). When a proxy is created
the server is queried for its version, and you can specify your minimum requirement. Methods added in later versions
are tagged with the ``@RPCSinceVersion`` annotation. If you try to use a method that the server isn't advertising support
of, an ``UnsupportedOperationException`` is thrown. If you want to know the version of the server, just use the
``protocolVersion`` property (i.e. ``getProtocolVersion`` in Java).

Thread safety
-------------
A proxy is thread safe, blocking, and allows multiple RPCs to be in flight at once. Any observables that are returned and
you subscribe to will have objects emitted in order on a background thread pool. Each Observable stream is tied to a single
thread, however note that two separate Observables may invoke their respective callbacks on different threads.

Error handling
--------------
If something goes wrong with the RPC infrastructure itself, an ``RPCException`` is thrown. If you call a method that
requires a higher version of the protocol than the server supports, ``UnsupportedOperationException`` is thrown.
Otherwise, if the server implementation throws an exception, that exception is serialised and rethrown on the client
side as if it was thrown from inside the called RPC method. These exceptions can be caught as normal.

Wire protocol
-------------
The client RPC wire protocol is defined and documented in ``net/corda/client/rpc/RPCApi.kt``.

Whitelisting classes with the Corda node
----------------------------------------
CorDapps must whitelist any classes used over RPC with Corda's serialization framework, unless they are whitelisted by
default in ``DefaultWhitelist``. The whitelisting is done either via the plugin architecture or by using the
``@CordaSerializable`` annotation.  See :doc:`serialization`. An example is shown in :doc:`tutorial-clientrpc-api`.

.. _CordaRPCClient: api/javadoc/net/corda/client/rpc/CordaRPCClient.html
.. _CordaRPCOps: api/javadoc/net/corda/core/messaging/CordaRPCOps.html
.. _CordaRPCConnection: api/javadoc/net/corda/client/rpc/CordaRPCConnection.html
