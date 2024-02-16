package com.jovitcorreia.app;

import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;

import java.security.InvalidParameterException;
import java.util.NoSuchElementException;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static jakarta.ws.rs.core.Response.Status.NOT_FOUND;

@Path("/clientes")
public class App {
  private final PgPool pool;

  @Inject
  public App(PgPool pool) {
    this.pool = pool;
  }

  @POST
  @Consumes(APPLICATION_JSON)
  @Produces(APPLICATION_JSON)
  @Path("/{id}/transacoes")
  public Uni<Response> realizaTransacao(@PathParam("id") String id, JsonObject txn) {
    return parseId(id)
      .onItem()
      .ifNotNull()
      .transformToUni(
        parsedId ->
          validateTxn(txn)
            .onItem()
            .ifNotNull()
            .transformToUni(
              validTxn ->
                processTxn(parsedId, validTxn)
                  .onItem()
                  .transform(result -> Response.ok(result).build())
                  .onFailure()
                  .recoverWithItem(Response.status(422).build()))
            .onItem()
            .ifNull()
            .continueWith(() -> Response.status(422).build()))
      .onItem()
      .ifNull()
      .continueWith(() -> Response.status(404).build());
  }

  @GET
  @Path("/{id}/extrato")
  @Produces(APPLICATION_JSON)
  public Uni<Response> getStmt(@PathParam("id") String id) {
    return parseId(id)
      .onItem().transformToUni(parsedId -> {
        if (parsedId != null) {
          return fetchStmt(parsedId)
            .onItem()
            .transform(result -> Response.ok(result).build())
            .onFailure()
            .recoverWithItem(th -> Response.status(404).build());
        } else {
          return Uni.createFrom().item(Response.status(404).build());
        }
      });
  }

  public Uni<Integer> parseId(String id) {
    return Uni.createFrom()
      .item(id)
      .onItem()
      .transform(Integer::parseInt)
      .onItem()
      .transformToUni(
        parsedId -> {
          if (parsedId >= 1 && parsedId <= 5) {
            return Uni
              .createFrom()
              .item(parsedId);
          } else {
            return Uni
              .createFrom()
              .failure(new InvalidParameterException());
          }
        })
      .onFailure()
      .recoverWithNull();
  }

  private Uni<JsonObject> validateTxn(JsonObject txn) {
    return Uni.createFrom()
      .item(txn)
      .onItem()
      .ifNotNull()
      .transformToUni(
        transaction -> {
          var value = transaction.getValue("valor");
          if (!(value instanceof Integer || value instanceof Long)
            || ((Number) value).longValue() <= 0) {
            return Uni.createFrom().failure(new IllegalArgumentException());
          }
          var type = transaction.getString("tipo", "");
          if (!"c".equals(type) && !"d".equals(type)) {
            return Uni.createFrom().failure(new IllegalArgumentException());
          }
          var desc = transaction.getString("descricao", "");
          if (desc == null || desc.isEmpty() || desc.length() > 10) {
            return Uni.createFrom().failure(new IllegalArgumentException());
          }
          return Uni.createFrom().item(transaction);
        })
      .onFailure()
      .recoverWithNull();
  }

  private Uni<JsonObject> processTxn(int holderId, JsonObject txn) {
    return Uni.createFrom()
      .item(
        () -> {
          Long value = txn.getLong("valor");
          String type = txn.getString("tipo");
          String desc = txn.getString("descricao");
          return Tuple.of(holderId, desc, type, value);
        })
      .onItem()
      .transformToUni(params -> performQuery(SET_TXN, params));
  }

  private Uni<JsonObject> fetchStmt(int holderId) {
    return Uni.createFrom()
      .item(() -> Tuple.of(holderId))
      .onItem()
      .transformToUni(params -> performQuery(GET_STMT, params));
  }

  public Uni<JsonObject> performQuery(String query, Tuple params) {
    return pool.preparedQuery(query)
      .execute(params)
      .onItem()
      .transformToUni(
        result -> {
          if (result.size() > 0) {
            var row = result.iterator().next();
            JsonObject responseBody = row.getJsonObject(0);
            return Uni.createFrom().item(responseBody);
          } else {
            return Uni.createFrom().failure(new NoSuchElementException());
          }
        });
  }

  private static final String SET_TXN = "SELECT set_txn($1, $2, $3, $4)";

  private static final String GET_STMT = "SELECT get_stmt($1)";
}
