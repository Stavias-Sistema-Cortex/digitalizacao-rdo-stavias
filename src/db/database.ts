import mysql from "mysql2/promise";

async function testarConexao() {
  try {
    const connection = await mysql.createConnection({
      host: "127.0.0.1",
      port: 3306,
      user: "teste_node",
      password: "Node123!",
    });

    console.log("Conectado!");

    const [rows] = await connection.query(
      "SELECT CURRENT_USER() AS usuario"
    );

    console.log(rows);

    await connection.end();
  } catch (error) {
    console.error("ERRO:", error);
  }
}

testarConexao();