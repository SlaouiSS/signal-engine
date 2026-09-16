import { useState } from 'react';

import ActivityPage from './activity/ActivityPage';
import HomePage from './home/HomePage';
import InterestsPage from './interests/InterestsPage';
import QaPage from './qa/QaPage';
import SearchPage from './search/SearchPage';
import SignalsPage from './signals/SignalsPage';
import SourcesPage from './sources/SourcesPage';

type Tab = 'home' | 'sources' | 'interests' | 'signals' | 'search' | 'qa' | 'activity';

const TABS: { id: Tab; label: string }[] = [
  { id: 'home', label: 'Home' },
  { id: 'sources', label: 'Sources' },
  { id: 'interests', label: 'Interests' },
  { id: 'signals', label: 'Signals' },
  { id: 'search', label: 'Search' },
  { id: 'qa', label: 'Q&A' },
  { id: 'activity', label: 'Activity' },
];

/**
 * Application shell. A plain in-memory tab switch, not a router: only seven screens exist so far
 * (docs/11-roadmap.md Phase 9), and no routing decision has been made for the project yet. Home is
 * the default screen — the main dashboard, shown first when the application opens.
 */
export default function App() {
  const [activeTab, setActiveTab] = useState<Tab>('home');

  return (
    <div className="app-shell">
      <header className="app-header">
        <div className="app-header-inner">
          <div className="brand">
            <span className="brand-mark" aria-hidden="true" />
            <h1>Signal Engine</h1>
          </div>

          <nav className="app-nav" aria-label="Main">
            {TABS.map((tab) => (
              <button
                key={tab.id}
                type="button"
                className="nav-button"
                onClick={() => setActiveTab(tab.id)}
                aria-current={activeTab === tab.id ? 'page' : undefined}
              >
                {tab.label}
              </button>
            ))}
          </nav>
        </div>
      </header>

      <main className="app-main">
        <div className="container">
          {activeTab === 'home' && <HomePage />}
          {activeTab === 'sources' && <SourcesPage />}
          {activeTab === 'interests' && <InterestsPage />}
          {activeTab === 'signals' && <SignalsPage />}
          {activeTab === 'search' && <SearchPage />}
          {activeTab === 'qa' && <QaPage />}
          {activeTab === 'activity' && <ActivityPage />}
        </div>
      </main>
    </div>
  );
}
