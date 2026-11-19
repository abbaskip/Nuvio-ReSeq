"""Serve 2,000 scheduled games alongside the ordinary profile journey inputs."""
from http.server import ThreadingHTTPServer
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "baselineprofile/fixtures"))
import server

original_schedule = server.sports


def schedule():
    data = original_schedule()
    body = data["entries"][0]["payload"]
    game = body["events"][0]
    body["events"] = [dict(game, id=str(77701 + i), name=f"Fixture game {i}") for i in range(2_000)]
    return data


if __name__ == "__main__":
    server.sports = schedule
    with ThreadingHTTPServer(("127.0.0.1", 38791), server.Web) as http:
        http.serve_forever()
