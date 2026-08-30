package vkauth

import (
	"encoding/json"
	"sync"

	"github.com/samosvalishe/free-turn-proxy/internal/statedir"
)

// PersonaStateFile - имя файла с поколением персоны рядом с client_config.json.
const PersonaStateFile = "vk_persona.json"

// personaState переживает перезапуск: без него сожжённый отпечаток воскресает на
// старте и VK каждый раз видит тот, что уже отверг. Seed фиксирует, чьё это
// поколение - смена -client-id даёт новую личность и счётчик с нуля.
type personaState struct {
	Seed string `json:"seed"`
	Gen  int    `json:"gen"`
}

// genStore с пустым paths - no-op (тесты и хосты без записи на диск).
type genStore struct {
	paths []string
}

func (s genStore) load(seed string) int {
	for _, b := range statedir.ReadEach(s.paths) {
		var st personaState
		if json.Unmarshal(b, &st) == nil && st.Seed == seed && st.Gen > 0 {
			return st.Gen
		}
	}
	return 0
}

// genMu сериализует load+write: сравнение с диском и запись должны быть одной
// операцией, иначе параллельные save затрут друг друга.
//
//nolint:gochecknoglobals // единственный писатель файла на процесс, лок процессный
var genMu sync.Mutex

// save не понижает поколение: несколько vk.Provider (multi-link) делят один файл
// и сжигают персоны независимо.
func (s genStore) save(seed string, gen int) bool {
	if len(s.paths) == 0 {
		return false
	}
	genMu.Lock()
	defer genMu.Unlock()
	if gen <= s.load(seed) {
		return true
	}
	b, err := json.Marshal(personaState{Seed: seed, Gen: gen})
	if err != nil {
		return false
	}
	return statedir.WriteFirst(s.paths, b)
}
