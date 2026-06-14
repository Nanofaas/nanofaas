package nanofaas

import (
	"encoding/json"
	"net/http"
)

func (r *Runtime) handleHealth(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
}
