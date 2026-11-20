import React from 'react';
import {createRoot} from 'react-dom/client';
import {TestingChecklist} from '../../src/testing-checklist.jsx';
createRoot(document.getElementById('root')).render(window.testOwner ? <TestingChecklist/> : <div>Public workspace</div>);
