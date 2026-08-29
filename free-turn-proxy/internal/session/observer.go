package session

// Observer получает уведомления об изменении фазы и состояния стримов сессии.
type Observer interface {
	OnPhase(phase Phase, streams, total int, errMsg string)
}
